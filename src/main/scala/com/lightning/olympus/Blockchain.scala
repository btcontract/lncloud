package com.lightning.olympus

import com.lightning.olympus.Utils.{bitcoin, values}
import com.lightning.wallet.ln.wire.ChannelAnnouncement
import com.lightning.wallet.ln.LightningException
import fr.acinq.bitcoin.BinaryData
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
    val txid = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val output = bitcoin.getTxOut(txid, ca.outputIndex, true)

    // Omit dust channels which are likely not capable of routing
    if (values.minAmount > output.value) throw new LightningException
    ChanInfo(txid, output.scriptPubKey, ca)
  }

  def getRawTxData(txid: String) = Try {
    BinaryData(bitcoin getRawTransactionHex txid)
  }

  def sendRawTx(binary: BinaryData) = Try {
    bitcoin sendRawTransaction binary.toString
  }
}


