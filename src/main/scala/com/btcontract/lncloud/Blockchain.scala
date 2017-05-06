package com.btcontract.lncloud

import com.btcontract.lncloud.JsonHttpUtils._
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient._

import zeromq.{SocketRef, SocketType, ZeroMQ}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import rx.lang.scala.{Subscription, Observable => Obs}
import com.btcontract.lncloud.Utils.{bitcoin, values, errLog}
import com.lightning.wallet.ln.wire.ChannelAnnouncement
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools.none
import scala.util.Try


case class ChanInfo(txid: String, txo: TxOut, ca: ChannelAnnouncement)
case class ChanDirection(channelId: Long, from: BinaryData, to: BinaryData)

trait BlockchainListener {
  def onNewBlock(block: Block): Unit = none
  def onNewTx(tx: Transaction): Unit = none
}

object Blockchain { me =>
  mkObserver("rawtx").subscribeOn(IOScheduler.apply)
    .map(Transaction read _).retryWhen(_ delay 10.second)
    .subscribe(tx => listeners.foreach(_ onNewTx tx), errLog)

  private val realtime = mkObserver("hashblock")
  Obs.interval(10.minute).map(_ => bitcoin.getBestBlockHash).merge(realtime)
    .map(bitcoin getBlock _.toString).throttleLast(5.seconds).subscribeOn(IOScheduler.apply)
    .retryWhen(_ delay 10.second).subscribe(block => listeners.foreach(_ onNewBlock block), errLog)

  def rescanBlocks: Unit =
    bitcoin.getBlockCount match { case count =>
      for (num <- count - values.rewindRange to count)
        for (lst <- listeners) lst onNewBlock bitcoin.getBlock(num)
    }

  def isSpent(chanInfo: ChanInfo): Boolean = Try {
    bitcoin.getTxOut(chanInfo.txid, chanInfo.ca.outputIndex)
  }.isFailure

  def getInfo(ca: ChannelAnnouncement): Obs[ChanInfo] =
    obsOn(me doGetInfo ca, IOScheduler.apply)

  private def doGetInfo(ca: ChannelAnnouncement) = {
    val txid = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val output = bitcoin.getTxOut(txid, ca.outputIndex, true)
    ChanInfo(txid, output, ca)
  }

  private def mkObserver(topic: String) = Obs[BinaryData] { obs =>
    val subSocket: SocketRef = ZeroMQ.socket(SocketType.Sub)
    subSocket.connect(address = values.zmqPoint)
    subSocket.recvAll(obs onNext _(1).toArray)
    subSocket.subscribe(topic = topic)
    Subscription(subSocket.close)
  }

  private var listeners = Set.empty[BlockchainListener]
  def addListener(lst: BlockchainListener): Unit = listeners += lst
  def removeListener(lst: BlockchainListener): Unit = listeners -= lst
}


