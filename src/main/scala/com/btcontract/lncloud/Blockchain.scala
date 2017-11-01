package com.btcontract.lncloud

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient._
import com.lightning.wallet.ln.wire.ChannelAnnouncement
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools.none
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.util.Try

import com.btcontract.lncloud.Utils.{bitcoin, errLog, values}
import rx.lang.scala.{Subscription, Observable => Obs}
import fr.acinq.bitcoin.{Transaction, BinaryData}
import zeromq.{SocketRef, SocketType, ZeroMQ}


case class TransactionWithRaw(raw: BinaryData) { val tx = Transaction read raw }
case class ChanInfo(txid: String, key: ScriptPubKey, ca: ChannelAnnouncement)
case class ChanDirection(channelId: Long, from: PublicKey, to: PublicKey)

trait BlockchainListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: TransactionWithRaw): Unit = none
}

object Blockchain { me =>
  var listeners = Set.empty[BlockchainListener]
  mkObserver("rawtx").subscribeOn(IOScheduler.apply)
    .map(TransactionWithRaw).retryWhen(_ delay 10.second)
    .subscribe(twr => listeners.foreach(_ onNewTx twr), errLog)

  private val realtime = mkObserver("hashblock")
  Obs.interval(20.minute).map(_ => bitcoin.getBestBlockHash).merge(realtime)
    .map(bitcoin getBlock _.toString).throttleLast(5.seconds).subscribeOn(IOScheduler.apply)
    .retryWhen(_ delay 10.second).subscribe(block => listeners.foreach(_ onNewBlock block), errLog)

  def rescanBlocks = {
    val currentPoint = bitcoin.getBlockCount
    val pastPoint = currentPoint - values.rewindRange
    val blocks = pastPoint to currentPoint map bitcoin.getBlock
    for (block <- blocks) for (lst <- listeners) lst onNewBlock block
  }

  def isSpent(chanInfo: ChanInfo): Boolean = Try {
    // Absent output tx means it has been spent already
    bitcoin.getTxOut(chanInfo.txid, chanInfo.ca.outputIndex)
  }.isFailure

  def getInfo(ca: ChannelAnnouncement) = Try {
    val txid = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val output = bitcoin.getTxOut(txid, ca.outputIndex, true)
    ChanInfo(txid, output.scriptPubKey, ca)
  }

  private def mkObserver(topic: String) = Obs[BinaryData] { obs =>
    val subSocket: SocketRef = ZeroMQ.socket(SocketType.Sub)
    subSocket.connect(address = values.zmqApi)
    subSocket.recvAll(obs onNext _(1).toArray)
    subSocket.subscribe(topic = topic)
    Subscription(subSocket.close)
  }
}


