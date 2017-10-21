package com.lightning.wallet.ln

import java.nio.ByteOrder._
import fr.acinq.bitcoin.Protocol._
import com.lightning.wallet.ln.crypto.MultiStreamUtils._
import com.lightning.wallet.ln.wire.ChannelUpdate
import fr.acinq.bitcoin.Crypto.PublicKey


object PaymentHop {
  type PublicPaymentRoute = Vector[Hop]
  type ExtraPaymentRoute = Vector[ExtraHop]
  type PaymentRoute = Vector[PaymentHop]
}

trait PaymentHop {
  def nextFee(msat: Long): Long
  def shortChannelId: Long
  def cltvExpiryDelta: Int
  def nodeId: PublicKey
}

case class ExtraHop(nodeId: PublicKey, shortChannelId: Long, fee: Long, cltvExpiryDelta: Int) extends PaymentHop {
  def pack = aconcat(nodeId.toBin.data.toArray, writeUInt64(shortChannelId, BIG_ENDIAN), writeUInt64(fee, BIG_ENDIAN),
    writeUInt16(cltvExpiryDelta, BIG_ENDIAN).data.toArray, Array.emptyByteArray)

  // Already pre-calculated
  def nextFee(msat: Long) = fee
}

case class PerHopPayload(channel_id: Long, amt_to_forward: Long, outgoing_cltv_value: Int)
case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate) extends PaymentHop {
  // Fee is not pre-calculated for public hops so we need to derive it accoring to rules specified in BOLT 04
  def nextFee(msat: Long) = lastUpdate.feeBaseMsat + (lastUpdate.feeProportionalMillionths * msat) / 1000000L
  def cltvExpiryDelta = lastUpdate.cltvExpiryDelta
  def shortChannelId = lastUpdate.shortChannelId
}