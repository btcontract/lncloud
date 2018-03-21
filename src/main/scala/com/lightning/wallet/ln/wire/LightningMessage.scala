package com.lightning.wallet.ln.wire

import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import com.lightning.wallet.ln.Tools.fromShortId
import com.lightning.wallet.ln.Hop
import fr.acinq.eclair.UInt64


trait LightningMessage
trait RoutingMessage extends LightningMessage
trait ChannelSetupMessage extends LightningMessage
trait ChannelMessage extends LightningMessage { val channelId: BinaryData }

// BASIC MESSAGES: channels never get these

case class Init(globalFeatures: BinaryData, localFeatures: BinaryData) extends LightningMessage
case class Ping(pongLength: Int, data: BinaryData) extends LightningMessage
case class Pong(data: BinaryData) extends LightningMessage

// CHANNEL SETUP MESSAGES: open channels never get these

case class OpenChannel(chainHash: BinaryData, temporaryChannelId: BinaryData, fundingSatoshis: Long, pushMsat: Long,
                       dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: UInt64, channelReserveSatoshis: Long, htlcMinimumMsat: Long,
                       feeratePerKw: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int, fundingPubkey: PublicKey, revocationBasepoint: Point,
                       paymentBasepoint: Point, delayedPaymentBasepoint: Point, htlcBasepoint: Point, firstPerCommitmentPoint: Point,
                       channelFlags: Byte) extends ChannelSetupMessage

case class AcceptChannel(temporaryChannelId: BinaryData, dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: UInt64,
                         channelReserveSatoshis: Long, htlcMinimumMsat: Long, minimumDepth: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey, revocationBasepoint: Point, paymentBasepoint: Point, delayedPaymentBasepoint: Point,
                         htlcBasepoint: Point, firstPerCommitmentPoint: Point) extends ChannelSetupMessage {

  lazy val dustLimitSat = Satoshi(dustLimitSatoshis)
}

case class FundingCreated(temporaryChannelId: BinaryData, fundingTxid: BinaryData,
                          fundingOutputIndex: Int, signature: BinaryData) extends ChannelSetupMessage

case class FundingSigned(channelId: BinaryData, signature: BinaryData) extends ChannelSetupMessage

// CHANNEL MESSAGES

case class FundingLocked(channelId: BinaryData, nextPerCommitmentPoint: Point) extends ChannelMessage
case class ClosingSigned(channelId: BinaryData, feeSatoshis: Long, signature: BinaryData) extends ChannelMessage
case class Shutdown(channelId: BinaryData, scriptPubKey: BinaryData) extends ChannelMessage

case class UpdateAddHtlc(channelId: BinaryData, id: Long,
                         amountMsat: Long, paymentHash: BinaryData, expiry: Long,
                         onionRoutingPacket: BinaryData) extends ChannelMessage {

  val amount = MilliSatoshi(amountMsat)
}

case class UpdateFailHtlc(channelId: BinaryData, id: Long, reason: BinaryData) extends ChannelMessage
case class UpdateFailMalformedHtlc(channelId: BinaryData, id: Long, onionHash: BinaryData, failureCode: Int) extends ChannelMessage
case class UpdateFulfillHtlc(channelId: BinaryData, id: Long, paymentPreimage: BinaryData) extends ChannelMessage {

  val paymentHash = fr.acinq.bitcoin.Crypto sha256 paymentPreimage.data
}

case class UpdateFee(channelId: BinaryData, feeratePerKw: Long) extends ChannelMessage
case class CommitSig(channelId: BinaryData, signature: BinaryData, htlcSignatures: List[BinaryData] = Nil) extends ChannelMessage
case class RevokeAndAck(channelId: BinaryData, perCommitmentSecret: Scalar, nextPerCommitmentPoint: Point) extends ChannelMessage

case class Error(channelId: BinaryData, data: BinaryData) extends ChannelMessage {
  def humanText = if (text.trim.isEmpty) "Error message from remote peer" else text
  val text = new String(data, "UTF-8")
}

case class ChannelReestablish(channelId: BinaryData, nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long, yourLastPerCommitmentSecret: Option[Scalar],
                              myCurrentPerCommitmentPoint: Option[Point] = None) extends ChannelMessage

// ROUTING MESSAGES: open channels never get these except for ChannelUpdate

case class AnnouncementSignatures(channelId: BinaryData, shortChannelId: Long, nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage

case class ChannelAnnouncement(nodeSignature1: BinaryData, nodeSignature2: BinaryData, bitcoinSignature1: BinaryData,
                               bitcoinSignature2: BinaryData, features: BinaryData, chainHash: BinaryData, shortChannelId: Long,
                               nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey) extends RoutingMessage {

  val (blockHeight, txIndex, outputIndex) = fromShortId(shortChannelId)
  lazy val nodes = Set(nodeId1, nodeId2)
}

case class NodeAnnouncement(signature: BinaryData, features: BinaryData,
                            timestamp: Long, nodeId: PublicKey, rgbColor: RGB, alias: String,
                            addresses: InetSocketAddressList) extends RoutingMessage {

  val identifier = (alias + nodeId.toString).toLowerCase
}

case class ChannelUpdate(signature: BinaryData, chainHash: BinaryData, shortChannelId: Long,
                         timestamp: Long, flags: BinaryData, cltvExpiryDelta: Int, htlcMinimumMsat: Long,
                         feeBaseMsat: Long, feeProportionalMillionths: Long) extends RoutingMessage {

  def feeEstimate = (feeBaseMsat + feeProportionalMillionths * 10).toDouble
  def toHop(nodeId: PublicKey) = Hop(nodeId, shortChannelId, cltvExpiryDelta,
    htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths)
}

// Not in a spec

case class WalletZygote(v: Int, db: BinaryData, wallet: BinaryData, chain: BinaryData)
case class AESZygote(v: Int, iv: BinaryData, ciphertext: BinaryData)