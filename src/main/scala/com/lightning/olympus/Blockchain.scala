package com.lightning.olympus

import rx.lang.scala.{Observable => Obs}
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.wire.ChannelAnnouncement
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.lightning.olympus.Utils.bitcoin
import fr.acinq.bitcoin.BinaryData
import scala.collection.mutable
import scala.util.Try


object Blockchain {
  def isSpent(chanInfo: ChanInfo) = Try {
    // Absent output tx means it has been spent already
    // attempting to get confirmations out of null result will fail here
    bitcoin.getTxOut(chanInfo.txid, chanInfo.ca.outputIndex).confirmations
  }.isFailure

  def isParentDeepEnough(txid: String) = Try {
    // Wait for parent depth before spending a child
    bitcoin.getRawTransaction(txid).confirmations > 1
  } getOrElse false

  def getChanInfo(ca: ChannelAnnouncement) = Try {
    val txid = getBlockByHeight(ca.blockHeight).tx.get(ca.txIndex)
    val amountBtc = bitcoin.getTxOut(txid, ca.outputIndex, true).value
    ChanInfo(txid, (BigDecimal(amountBtc) * 100000000L).toLong, ca)
  }

  def getRawTxData(txid: String) = Try {
    BinaryData(bitcoin getRawTransactionHex txid)
  }

  def sendRawTx(binary: BinaryData) = Try {
    bitcoin sendRawTransaction binary.toString
  }

  val getBlockByHeight: Int => Block = new mutable.HashMap[Int, Block] {
    // Memoize static block data, but clear every 6 hours to avoid memory leak
    override def apply(height: Int) = getOrElseUpdate(height, bitcoin getBlock height)
    Obs interval 6.hours foreach { _ => clear }
  }
}