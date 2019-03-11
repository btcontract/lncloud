package com.lightning.olympus

import com.lightning.walletapp.ln.wire.ChannelAnnouncement
import com.lightning.olympus.Utils.bitcoin
import fr.acinq.bitcoin.BinaryData
import scala.util.Try


object Blockchain { me =>
  def isParentDeepEnough(txid: String) = Try {
    // Wait for parent depth before spending a child
    bitcoin.getRawTransaction(txid).confirmations > 1
  } getOrElse false

  def getChanInfo(ca: ChannelAnnouncement) = Try {
    val txid = TxCache.getTxListByBlock(ca.blockHeight)(ca.txIndex)
    val amountBtc = bitcoin.getTxOut(txid, ca.outputIndex, false).value
    val amountSat = BigDecimal(amountBtc) * 100000000L
    ChanInfo(txid, amountSat.toLong, ca)
  }

  def getRawTxData(txid: String) = Try {
    BinaryData(bitcoin getRawTransactionHex txid)
  }

  def sendRawTx(binary: BinaryData) = Try {
    bitcoin sendRawTransaction binary.toString
  }
}