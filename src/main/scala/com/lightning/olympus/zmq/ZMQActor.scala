package com.lightning.olympus.zmq

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._

import com.lightning.walletapp.ln.wire.LightningMessageCodecs.revocationInfoCodec
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.olympus.database.Database
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Helpers
import com.lightning.walletapp.helper.AES
import scala.collection.mutable
import scodec.bits.BitVector
import org.zeromq.ZMQ.Event
import scodec.DecodeResult
import akka.actor.Actor

import com.lightning.olympus.Utils.{bitcoin, values}
import com.lightning.olympus.{Blockchain, Router}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import org.zeromq.{ZContext, ZMQ, ZMsg}


class ZMQActor(db: Database) extends Actor {
  // Reads messages in a non-blocking manner with an interval
  // should be restarted from outside in case of disconnect

  val rmSpent = "Removed spent channels"
  val removeSpentChannels = new ZMQListener {
    override def onNewTx(twr: TransactionWithRaw) = for {
      // We need to check if any input spends a channel output
      // related payment channels should be removed

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
    override def onNewBlock(block: Block) = for {
      // We need to save which txids this one spends from
      // since clients may need this to extract preimages

      txid <- block.tx.asScala.par
      binary <- Blockchain getRawTxData txid
      transactionWithRaw = TransactionWithRaw(binary)
      parents = transactionWithRaw.tx.txIn.map(_.outPoint.txid.toString)
    } db.putTx(txids = parents, prefix = txid, hex = binary.toString)
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
    val publishes: mutable.Map[BinaryData, RevokedCommitPublished] =
      new ConcurrentHashMap[BinaryData, RevokedCommitPublished].asScala

    def publishPunishments = for {
      txId \ RevokedCommitPublished(claimMain, claimTheirMainPenalty, htlcPenalty, _) <- publishes
      _ = println(s"Re-broadcasting a punishment package for breached transaction $txId")
      transactionWithInputInfo <- claimMain ++ claimTheirMainPenalty ++ htlcPenalty
    } Blockchain.sendRawTx(Transaction write transactionWithInputInfo.tx)

    override def onNewBlock(block: Block) = {
      val halfTxIds = for (txid <- block.tx.asScala) yield txid take 16
      val half2Full = halfTxIds.zip(block.tx.asScala).toMap
      if (block.height % 1440 == 0) publishes.clear

      for {
        halfTxId \ aesz <- db.getWatched(halfTxIds.toVector)
        fullTxidBin <- half2Full get halfTxId map BinaryData.apply
        revBitVec <- AES.decZygote(aesz, fullTxidBin) map BitVector.apply
        DecodeResult(ri, _) <- revocationInfoCodec.decode(revBitVec).toOption
        twr <- Blockchain getRawTxData fullTxidBin.toString map TransactionWithRaw
        rcp = Helpers.Closing.claimRevokedRemoteCommitTxOutputs(ri, twr.tx)
      } publishes.put(fullTxidBin, rcp)

      // Broadcast txs
      publishPunishments
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
    val blocks = pastPoint to currentPoint map bitcoin.getBlock
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