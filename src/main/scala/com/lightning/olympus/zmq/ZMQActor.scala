package com.lightning.olympus.zmq

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._

import org.zeromq.{ZContext, ZMQ, ZMsg}
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.olympus.Utils.{bitcoin, blockchain, values}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListSet}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.revocationInfoCodec
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.olympus.database.Database
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Helpers
import com.lightning.walletapp.helper.AES
import com.lightning.olympus.Router
import scala.collection.mutable
import scodec.bits.BitVector
import org.zeromq.ZMQ.Event
import scodec.DecodeResult
import akka.actor.Actor


class ZMQActor(db: Database) extends Actor {
  // Reads messages in a non-blocking manner with an interval
  // should be restarted from outside in case of disconnect

  val checkTransactions = new ZMQListener {
    override def onNewTx(tx: Transaction) = for {
      // We need to check if any input spends a channel output
      // related payment channels should be removed immediately

      input <- tx.txIn
      chanInfo <- Router.txId2Info.get(input.outPoint.txid)
      if chanInfo.ca.outputIndex == input.outPoint.index
      text = s"Removing chan $chanInfo because spent"
    } Router.complexRemove(chanInfo :: Nil, text)

    override def onNewBlock(block: Block) = {
      // We need to save which txids this one spends from since clients may need this to extract preimages
      // then we need to check if any transaction contained in a block spends any of existing channels
      log(s"Recording block ${block.height} to db")

      for {
        txid <- block.tx.asScala.par
        rawBin <- blockchain.getRawTxData(txid)
        transaction = Transaction.read(in = rawBin)
        parents = transaction.txIn.map(_.outPoint.txid.toString)
        _ = db.putSpender(txids = parents, prefix = txid)
      } onNewTx(tx = transaction)
    }
  }

  val sendScheduled = new ZMQListener {
    override def onNewBlock(block: Block) = for {
      // We broadcast all txs with cleared CLTV timeout
      // whose parents have at least two confirmations
      // CSV timeout will be rejected by blockchain

      rawStr <- db getScheduled block.height
      txInputs = Transaction.read(rawStr).txIn
      parents = txInputs.map(_.outPoint.txid.toString)
      if parents forall blockchain.isParentDeepEnough
    } blockchain.sendRawTx(rawStr)
  }

  val sendWatched = new ZMQListener {
    // A map from parent breach txid to punishing data
    // need to keep these around and re-publish a few times
    val publishes: mutable.Map[BinaryData, RevokedCommitPublished] =
      new ConcurrentHashMap[BinaryData, RevokedCommitPublished].asScala

    // Try to publish breaches periodically to not query db on each incoming transaction
    val txidAccumulator: mutable.Set[String] = new ConcurrentSkipListSet[String].asScala
    Obs.interval(60.seconds).foreach(_ => collectAndPublishPunishments, Tools.errlog)

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
        tx <- blockchain.getRawTxData(fullTxidBin.toString).map(Transaction read _)
        rcp = Helpers.Closing.claimRevokedRemoteCommitTxOutputs(ri, tx)
      } publishes.put(fullTxidBin, rcp)

      for {
        txId \ RevokedCommitPublished(claimMain, claimTheirMainPenalty, htlcPenalty, _) <- publishes
        _ = log(s"Re-broadcasting a punishment transactions for breached channel funding $txId")
        transactionWithInputInfo <- claimMain ++ claimTheirMainPenalty ++ htlcPenalty
      } blockchain.sendRawTx(Transaction write transactionWithInputInfo.tx)
    }

    override def onNewTx(tx: Transaction) =
      txidAccumulator += tx.txid.toString

    override def onNewBlock(block: Block) = {
      if (block.height % 1440 == 0) publishes.clear
      txidAccumulator ++= block.tx.asScala
    }
  }

  val ctx = new ZContext
  val subscriber = ctx.createSocket(ZMQ.SUB)
  val listeners = Set(sendScheduled, sendWatched, checkTransactions)
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
    val fullBlock = bitcoin.getBlock(hash.toString)
    listeners.foreach(_ onNewBlock fullBlock)
  }

  def gotRawTx(raw: BinaryData) = {
    val transaction = Transaction.read(raw)
    listeners.foreach(_ onNewTx transaction)
  }

  def rescanBlocks = {
    val currentPoint = bitcoin.getBlockCount
    val pastPoint = currentPoint - values.rewindRange
    val blocks = pastPoint to currentPoint map bitcoin.getBlock
    for (block <- blocks) for (lst <- listeners) lst onNewBlock block
    log("Done rescanning blocks")
  }
}

trait ZMQListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: Transaction): Unit = none
}