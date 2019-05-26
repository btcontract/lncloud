package com.lightning.olympus.zmq

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._

import rx.lang.scala.{Observable => Obs}
import scodec.bits.{BitVector, ByteVector}
import org.zeromq.{SocketType, ZContext, ZMQ, ZMsg}
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
import fr.acinq.bitcoin.Transaction
import scala.collection.mutable
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

      spendInput <- tx.txIn
      chanInfo <- Router.txId2Info.get(spendInput.outPoint.txid.toHex)
      if chanInfo.ca.outputIndex == spendInput.outPoint.index
      text = s"Removing chan $chanInfo because spent"
    } Router.complexRemove(chanInfo :: Nil, text)

    override def onNewBlock(block: Block) = {
      // We need to save which txids this one spends from since clients may need this to extract preimages
      // then we need to check if any transaction contained in a block spends any of existing channels
      log(s"Recording block ${block.height} to db")

      for {
        txid <- block.tx.asScala.par
        rawHex <- blockchain.getRawTxData(txid)
        transaction = Transaction.read(in = rawHex)
        parents = transaction.txIn.map(_.outPoint.txid.toHex)
        _ = db.putSpender(txids = parents, prefix = txid)
      } onNewTx(tx = transaction)
    }
  }

  val sendScheduled = new ZMQListener {
    override def onNewBlock(block: Block) = for {
      // We broadcast all txs with cleared CLTV timeout
      // whose parents have at least two confirmations
      // CSV timeout will be rejected by blockchain

      rawTxHex <- db.getScheduled(block.height)
      txInputs = Transaction.read(rawTxHex).txIn
      parentTxIds = txInputs.map(_.outPoint.txid.toHex)
      if parentTxIds forall blockchain.isParentDeepEnough
    } blockchain.sendRawTx(rawTxHex)
  }

  val sendWatched = new ZMQListener {
    // A map from parent breach txid to punishing data
    // need to keep these around and re-publish a few times
    val publishes: mutable.Map[String, RevokedCommitPublished] =
      new ConcurrentHashMap[String, RevokedCommitPublished].asScala

    // Try to publish breaches periodically to not query db on each incoming transaction
    val txidAccumulator: mutable.Set[String] = new ConcurrentSkipListSet[String].asScala
    Obs.interval(120.seconds).foreach(_ => collectAndPublishPunishments, Tools.errlog)

    def collectAndPublishPunishments = {
      val collectedTxIds = txidAccumulator.toVector
      val halfTxIds = for (txid <- collectedTxIds) yield txid take 16
      val half2FullMap = halfTxIds.zip(collectedTxIds).toMap
      log(s"Checking ${txidAccumulator.size} spends")
      val timer = System.currentTimeMillis
      txidAccumulator.clear

      for {
        // Obtain matched txs and get their full txids
        halfTxId \ aesz <- db.getWatched(halfTxIds)
        fullTxid <- half2FullMap get halfTxId

        // Decrypt punishment blob and decode RevocationInfo
        revBitVec <- AES.decZygote(aesz, ByteVector.fromValidHex(fullTxid).toArray)
        DecodeResult(ri, _) <- revocationInfoCodec.decode(BitVector apply revBitVec).toOption

        // Get parent tx from chain, generate punishment, schedule spend
        parentTx <- blockchain.getRawTxData(fullTxid).map(Transaction.read)
        rcp = Helpers.Closing.claimRevokedRemoteCommitTxOutputs(ri, parentTx)
      } publishes.put(fullTxid, rcp)

      val span = System.currentTimeMillis - timer
      log(s"Watched txs fetching took $span msecs")

      for {
        txId \ RevokedCommitPublished(claimMain, claimTheirMainPenalty, htlcPenalty, _) <- publishes
        _ = log(s"Re-broadcasting a punishment transactions for breached channel funding $txId")
        transactionWithInputInfo <- claimMain ++ claimTheirMainPenalty ++ htlcPenalty
      } blockchain.sendRawTx(transactionWithInputInfo.tx.bin.toHex)
    }

    override def onNewTx(tx: Transaction) =
      txidAccumulator += tx.txid.toHex

    override def onNewBlock(block: Block) = {
      if (block.height % 1440 == 0) publishes.clear
      txidAccumulator ++= block.tx.asScala
    }
  }

  val ctx = new ZContext
  val subscriber = ctx.createSocket(SocketType.SUB)
  val listeners = Set(sendScheduled, sendWatched, checkTransactions)
  subscriber.monitor("inproc://events", ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED)
  subscriber.subscribe("hashblock" getBytes ZMQ.CHARSET)
  subscriber.subscribe("rawtx" getBytes ZMQ.CHARSET)
  subscriber.connect(values.zmqApi)

  val monitor = ctx.createSocket(SocketType.PAIR)
  monitor.connect("inproc://events")

  def receive = {
    case msg: ZMsg => msg.popString match {
      case "hashblock" => gotBlockHash(msg.pop.getData)
      case "rawtx" => gotRawTx(msg.pop.getData)
      case _ => log("Unexpected topic")
    }

    case event: Event => event.getEvent match {
      case ZMQ.EVENT_DISCONNECTED => throw new Exception("ZMQ connection lost")
      case ZMQ.EVENT_CONNECTED => log("ZMQ connection established")
      case _ => log("Unexpected event")
    }

    case 'checkEvent =>
      val event = Event.recv(monitor, ZMQ.DONTWAIT)
      if (event != null) self ! event else reScheduleEvent

    case 'checkMsg =>
      val msg = ZMsg.recvMsg(subscriber, ZMQ.DONTWAIT)
      if (msg != null) self ! msg else reScheduleMsg

  }

  def reScheduleEvent = context.system.scheduler.scheduleOnce(1.second, self ,'checkEvent)
  def reScheduleMsg = context.system.scheduler.scheduleOnce(1.second, self, 'checkMsg)

  def gotBlockHash(hash: Bytes) = {
    val blockHashHex = ByteVector.view(hash).toHex
    val fullBlock = bitcoin.getBlock(blockHashHex)
    listeners.foreach(_ onNewBlock fullBlock)
    self ! 'checkMsg
  }

  def gotRawTx(raw: Bytes) = {
    val transaction = Transaction.read(raw)
    listeners.foreach(_ onNewTx transaction)
    self ! 'checkMsg
  }

  val currentPoint = bitcoin.getBlockCount
  val pastPoint = currentPoint - values.rewindRange
  log(s"Starting blocks rescan $currentPoint - $pastPoint")
  val blocks = pastPoint to currentPoint map bitcoin.getBlock
  for (block <- blocks) for (lst <- listeners) lst onNewBlock block
  log("Done rescanning blocks")

  self ! 'checkEvent
  self ! 'checkMsg
}

trait ZMQListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: Transaction): Unit = none
}