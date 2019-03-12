package com.lightning.olympus

import com.lightning.walletapp.ln.wire.ChannelAnnouncement
import com.lightning.olympus.database.Database
import com.lightning.olympus.Utils.bitcoin
import fr.acinq.bitcoin.BinaryData
import language.postfixOps
import scala.util.Try


class Blockchain(db: Database) { me =>
  def isParentDeepEnough(txid: String) = Try {
    // Wait for parent depth before spending a child
    bitcoin.getRawTransaction(txid).confirmations > 1
  } getOrElse false

  def getChanInfo(ca: ChannelAnnouncement): Option[ChanInfo] =
    db.getChanInfo(ca.shortChannelId).map(_ toChanInfo ca)
      .orElse(doGetChanInfo(ca).toOption)

  private def doGetChanInfo(ca: ChannelAnnouncement) = Try {
    val channelFundingTxid = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val amountBtc = bitcoin.getTxOut(channelFundingTxid, ca.outputIndex, false).value
    val info = ChanInfo(channelFundingTxid, BigDecimal(amountBtc) * 100000000L toLong, ca)
    db.addChanInfo(info)
    info
  }

  def getRawTxData(txid: String) = Try {
    BinaryData(bitcoin getRawTransactionHex txid)
  }

  def sendRawTx(binary: BinaryData) = Try {
    bitcoin sendRawTransaction binary.toString
  }
}