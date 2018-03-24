package com.lightning.olympus

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient._
import com.lightning.wallet.ln.wire.ChannelAnnouncement
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools
import scala.util.Try

import rx.lang.scala.{Subscription, Observable => Obs}
import com.lightning.wallet.ln.Tools.{none, errlog}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import zeromq.{SocketType, ZeroMQ}
import Utils.{bitcoin, values}


case class TransactionWithRaw(raw: BinaryData) {
  // A wrapper with deserialized tx for performance
  val tx = Transaction read raw
}

trait BlockchainListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: TransactionWithRaw): Unit = none
}

object Blockchain { me =>
  var listeners = Set.empty[BlockchainListener]
  mkObserver("rawtx").subscribeOn(IOScheduler.apply)
    .map(TransactionWithRaw).map(v => { println("GOT RAW TX"); v } )
    .subscribe(twr => listeners.foreach(_ onNewTx twr), errlog)

  private val realtime = mkObserver("hashblock")
  Obs.interval(20.minute).map(_ => bitcoin.getBestBlockHash).merge(realtime)
    .map(bitcoin getBlock _.toString).throttleLast(5.seconds).subscribeOn(IOScheduler.apply)
    .map(v => { println("GOT BLOCK"); v } ).subscribe(block => listeners.foreach(_ onNewBlock block), errlog)

  def rescanBlocks = {
    val currentPoint = bitcoin.getBlockCount
    val pastPoint = currentPoint - values.rewindRange
    val blocks = pastPoint to currentPoint map bitcoin.getBlock
    for (block <- blocks) for (lst <- listeners) lst onNewBlock block
    Tools log "Done rescanning blocks"
  }

  def isSpent(chanInfo: ChanInfo) = Try {
    // Absent output tx means it has been spent already
    // attempting to get confirmations out of null result will fail here
    bitcoin.getTxOut(chanInfo.txid, chanInfo.ca.outputIndex).confirmations
  }.isFailure

  def isParentDeepEnough(txid: String) = Try {
    // Wait for parent depth before spending a child
    bitcoin.getRawTransaction(txid).confirmations > 1
  } getOrElse false

  def getInfo(ca: ChannelAnnouncement) = Try {
    val txid = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val output = bitcoin.getTxOut(txid, ca.outputIndex, true)
    ChanInfo(txid, output.scriptPubKey, ca)
  }

  def mkObserver(zmqTopic: String) = Obs[BinaryData] { obs =>
    val subSocket = ZeroMQ.socket(socketType = SocketType.Sub)
    subSocket.connect(address = values.zmqApi)
    subSocket.recvAll(obs onNext _(1).toArray)
    subSocket.subscribe(topic = zmqTopic)
    Subscription(subSocket.close)
  }
}


