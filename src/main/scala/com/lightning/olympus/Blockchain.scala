package com.lightning.olympus

import com.lightning.olympus.Utils.bitcoin
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

  def getInfo(ca: com.lightning.wallet.ln.wire.ChannelAnnouncement) = Try {
    val transactionIdentifier = bitcoin.getBlock(ca.blockHeight).tx.get(ca.txIndex)
    val output = bitcoin.getTxOut(transactionIdentifier, ca.outputIndex, true)
    ChanInfo(transactionIdentifier, output.scriptPubKey, ca)
  }

  def getRawTxData(txid: String) = Try {
    BinaryData(bitcoin getRawTransactionHex txid)
  }

  def sendRawTx(binary: BinaryData) = Try {
    bitcoin sendRawTransaction binary.toString
  }
}


