package com.lightning.olympus.zmq

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._

import org.zeromq.{ZContext, ZMQ, ZMsg}
import rx.lang.scala.{Observable => Obs}
import com.lightning.olympus.{Blockchain, Router}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.olympus.Utils.{bitcoin, values}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListSet}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.revocationInfoCodec
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.olympus.database.Database
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Helpers
import com.lightning.walletapp.helper.AES
import scala.collection.mutable
import scodec.bits.BitVector
import org.zeromq.ZMQ.Event
import scodec.DecodeResult
import akka.actor.Actor


class ZMQActor(db: Database) extends Actor {
  // Reads messages in a non-blocking manner with an interval
  // should be restarted from outside in case of disconnect

  val rmSpent = "Removed spent channels"
  val removeSpentChannels = new ZMQListener {
    override def onNewTx(twr: TransactionWithRaw) = for {
      // We need to check if any input spends a channel output
      // related payment channels should be removed immediately

      input <- twr.tx.txIn.headOption
      chanInfo <- Router.txId2Info get input.outPoint.txid
      if chanInfo.ca.outputIndex == input.outPoint.index
    } Router.complexRemove(chanInfo :: Nil, rmSpent)

    override def onNewBlock(block: Block) = {
      val spent = Router.txId2Info.values filter Blockchain.isSpent
      if (spent.nonEmpty) Router.complexRemove(spent, rmSpent)
    }
  }

  val recordTransactions = new ZMQListener {
    override def onNewBlock(block: Block) = {
      // We need to save which txids this one spends from
      // since clients may need this to extract preimages
      log(s"Recording block ${block.height}")

      for {
        txid <- block.tx.asScala.par
        raw <- Blockchain getRawTxData txid
        txIns = TransactionWithRaw(raw).tx.txIn
        parents = txIns.map(_.outPoint.txid.toString)
      } db.putSpender(parents, prefix = txid)
    }
  }

  val sendScheduled = new ZMQListener {
    override def onNewBlock(block: Block) = for {
      // We broadcast all txs with cleared CLTV timeout
      // whose parents have at least two confirmations
      // CSV timeout will be rejected by blockchain

      raw <- db getScheduled block.height map BinaryData.apply
      parents = Transaction.read(raw).txIn.map(_.outPoint.txid.toString)
      if parents forall Blockchain.isParentDeepEnough
    } Blockchain.sendRawTx(raw)
  }

  val sendWatched = new ZMQListener {
    // A map from parent breach txid to punishing data
    // need to keep these around and re-publish a few times
    val publishes: mutable.Map[BinaryData, RevokedCommitPublished] =
      new ConcurrentHashMap[BinaryData, RevokedCommitPublished].asScala

    // Try to publish breaches periodically to not query db on each incoming transaction
    val txidAccumulator: mutable.Set[String] = new ConcurrentSkipListSet[String].asScala
    Obs.interval(90.seconds).foreach(_ => collectAndPublishPunishments, Tools.errlog)

    def collectAndPublishPunishments = {
      val collectedTxIds = txidAccumulator.toVector
      val halfTxIds = for (txid <- collectedTxIds) yield txid take 16
      val half2FullMap = halfTxIds.zip(collectedTxIds).toMap
      log(s"Checking ${txidAccumulator.size} spends")
      txidAccumulator.clear

      for {
        halfTxId \ aesz <- db.getWatched(halfTxIds)
        fullTxidBin <- half2FullMap get halfTxId map BinaryData.apply
        revBitVec <- AES.decZygote(aesz, fullTxidBin) map BitVector.apply
        DecodeResult(ri, _) <- revocationInfoCodec.decode(revBitVec).toOption
        twr <- Blockchain getRawTxData fullTxidBin.toString map TransactionWithRaw
        rcp = Helpers.Closing.claimRevokedRemoteCommitTxOutputs(ri, twr.tx)
      } publishes.put(fullTxidBin, rcp)

      for {
        txId \ RevokedCommitPublished(claimMain, claimTheirMainPenalty, htlcPenalty, _) <- publishes
        _ = log(s"Re-broadcasting a punishment transactions for breached channel funding $txId")
        transactionWithInputInfo <- claimMain ++ claimTheirMainPenalty ++ htlcPenalty
      } Blockchain.sendRawTx(Transaction write transactionWithInputInfo.tx)
    }

    override def onNewTx(twr: TransactionWithRaw) =
      txidAccumulator += twr.tx.txid.toString

    override def onNewBlock(block: Block) = {
      if (block.height % 1440 == 0) publishes.clear
      txidAccumulator ++= block.tx.asScala
    }
  }

  val ctx = new ZContext
  val subscriber = ctx.createSocket(ZMQ.SUB)
  val listeners = Set(sendScheduled, sendWatched, removeSpentChannels, recordTransactions)
  subscriber.monitor("inproc://events", ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED)
  subscriber.subscribe("hashblock" getBytes ZMQ.CHARSET)
  subscriber.subscribe("rawtx" getBytes ZMQ.CHARSET)
  subscriber.connect(values.zmqApi)

  val monitor = ctx.createSocket(ZMQ.PAIR)
  monitor.connect("inproc://events")

  def checkEvent: Unit = {
    val event = Event.recv(monitor, ZMQ.DONTWAIT)
    if (null != event) runAnd(self ! event.getEvent)(checkEvent)
    else context.system.scheduler.scheduleOnce(1.second)(checkEvent)
  }

  def checkMsg: Unit = {
    val zmqMessage = ZMsg.recvMsg(subscriber, ZMQ.DONTWAIT)
    if (null != zmqMessage) runAnd(self ! zmqMessage)(checkMsg)
    else context.system.scheduler.scheduleOnce(1.second)(checkMsg)
  }

  rescanBlocks
  checkEvent
  checkMsg

  def receive: Receive = {
    case msg: ZMsg => msg.popString match {
      case "hashblock" => gotBlockHash(msg.pop.getData)
      case "rawtx" => gotRawTx(msg.pop.getData)
      case _ => log("Unexpected topic")
    }
  }

  def gotBlockHash(hash: BinaryData) = {
    val fullBlock = bitcoin getBlock hash.toString
    listeners.foreach(_ onNewBlock fullBlock)
  }

  def gotRawTx(raw: BinaryData) = {
    val twr = TransactionWithRaw(raw)
    listeners.foreach(_ onNewTx twr)
  }

  def rescanBlocks = {
    val currentPoint = bitcoin.getBlockCount
    val pastPoint = currentPoint - values.rewindRange
    val blocks = pastPoint to currentPoint map Blockchain.getBlockByHeight
    for (block <- blocks) for (lst <- listeners) lst onNewBlock block
    log("Done rescanning blocks")
  }
}

case class TransactionWithRaw(raw: BinaryData) {
  // A wrapper with deserialized tx for performance
  val tx = Transaction read raw
}

trait ZMQListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: TransactionWithRaw): Unit = none
}