package com.lightning.olympus.zmq

import scala.collection.JavaConverters._
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.olympus.database.Database
import scala.concurrent.duration.DurationInt
import org.zeromq.ZMQ.Event
import akka.actor.Actor

import com.lightning.walletapp.ln.Tools.{log, none, runAnd}
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

      raw <- db getScheduled block.height
      tx = Transaction read BinaryData(raw)
      parents = tx.txIn.map(_.outPoint.txid.toString)
      if parents forall Blockchain.isParentDeepEnough
    } Blockchain sendRawTx raw
  }

  val ctx = new ZContext
  val subscriber = ctx.createSocket(ZMQ.SUB)
  val listeners = Set(removeSpentChannels, recordTransactions, sendScheduled)
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