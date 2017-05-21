package com.lightning.wallet.ln

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.PublicKey


case class Invoice(message: Option[String], nodeId: PublicKey,
                   sum: MilliSatoshi, paymentHash: BinaryData)

object Invoice {
  def serialize(invoice: Invoice) = {
    val hash = invoice.paymentHash.toString
    val node = invoice.nodeId.toString
    val sum = invoice.sum.amount
    s"$node:$sum:$hash"
  }

  def parse(encoded: String) = {
    val Array(node, sum, hash) = encoded.split(':')
    Invoice(None, PublicKey(node), MilliSatoshi(sum.toLong), hash)
  }
}
