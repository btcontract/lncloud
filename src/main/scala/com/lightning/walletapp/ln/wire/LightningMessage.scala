package com.lightning.walletapp.ln.wire

import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._

import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}
import com.lightning.walletapp.ln.{Features, HasCommitments, LightningException}
import com.lightning.walletapp.lnutils.olympus.OlympusWrap.StringVec
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.UInt64


trait LightningMessage
trait RoutingMessage extends LightningMessage
trait ChannelSetupMessage extends LightningMessage
trait ChannelMessage extends LightningMessage { val channelId: BinaryData }
case class Init(globalFeatures: BinaryData, localFeatures: BinaryData) extends LightningMessage
case class Ping(pongLength: Int, data: BinaryData) extends LightningMessage
case class Pong(data: BinaryData) extends LightningMessage

// CHANNEL SETUP MESSAGES: open channels never get these

case class ChannelFlags(flags: Byte) {
  def isPublic = Features.isBitSet(0, flags)
  def isZeroConfSpendablePush = Features.isBitSet(3, flags)
}

case class OpenChannel(chainHash: BinaryData, temporaryChannelId: BinaryData, fundingSatoshis: Long, pushMsat: Long,
                       dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: UInt64, channelReserveSatoshis: Long, htlcMinimumMsat: Long,
                       feeratePerKw: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int, fundingPubkey: PublicKey, revocationBasepoint: Point,
                       paymentBasepoint: Point, delayedPaymentBasepoint: Point, htlcBasepoint: Point, firstPerCommitmentPoint: Point,
                       channelFlags: ChannelFlags) extends ChannelSetupMessage

case class AcceptChannel(temporaryChannelId: BinaryData, dustLimitSatoshis: Long, maxHtlcValueInFlightMsat: UInt64,
                         channelReserveSatoshis: Long, htlcMinimumMsat: Long, minimumDepth: Long, toSelfDelay: Int, maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey, revocationBasepoint: Point, paymentBasepoint: Point, delayedPaymentBasepoint: Point,
                         htlcBasepoint: Point, firstPerCommitmentPoint: Point) extends ChannelSetupMessage {

  lazy val dustLimitSat = Satoshi(dustLimitSatoshis)
}

case class FundingCreated(temporaryChannelId: BinaryData,
                          fundingTxid: BinaryData, fundingOutputIndex: Int,
                          signature: BinaryData) extends ChannelSetupMessage

case class FundingSigned(channelId: BinaryData, signature: BinaryData) extends ChannelSetupMessage

// CHANNEL MESSAGES

case class FundingLocked(channelId: BinaryData, nextPerCommitmentPoint: Point) extends ChannelMessage
case class ClosingSigned(channelId: BinaryData, feeSatoshis: Long, signature: BinaryData) extends ChannelMessage
case class Shutdown(channelId: BinaryData, scriptPubKey: BinaryData) extends ChannelMessage

case class UpdateAddHtlc(channelId: BinaryData, id: Long,
                         amountMsat: Long, paymentHash: BinaryData, expiry: Long,
                         onionRoutingPacket: BinaryData) extends ChannelMessage {

  lazy val hash160 = Crypto ripemd160 paymentHash
  lazy val amount = MilliSatoshi(amountMsat)
}

case class UpdateFailHtlc(channelId: BinaryData, id: Long, reason: BinaryData) extends ChannelMessage
case class UpdateFailMalformedHtlc(channelId: BinaryData, id: Long, onionHash: BinaryData, failureCode: Int) extends ChannelMessage
case class UpdateFulfillHtlc(channelId: BinaryData, id: Long, paymentPreimage: BinaryData) extends ChannelMessage {

  val paymentHash = Crypto sha256 paymentPreimage.data
}

case class UpdateFee(channelId: BinaryData, feeratePerKw: Long) extends ChannelMessage
case class CommitSig(channelId: BinaryData, signature: BinaryData, htlcSignatures: List[BinaryData] = Nil) extends ChannelMessage
case class RevokeAndAck(channelId: BinaryData, perCommitmentSecret: Scalar, nextPerCommitmentPoint: Point) extends ChannelMessage

case class Error(channelId: BinaryData, data: BinaryData) extends ChannelMessage {
  def exception = new LightningException(reason = s"Remote channel message\n\n$text")
  lazy val text = bin2readable(data)
}

case class ChannelReestablish(channelId: BinaryData, nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long, yourLastPerCommitmentSecret: Option[Scalar],
                              myCurrentPerCommitmentPoint: Option[Point] = None) extends ChannelMessage

// ROUTING MESSAGES: open channels never get these except for ChannelUpdate

case class AnnouncementSignatures(channelId: BinaryData, shortChannelId: Long, nodeSignature: BinaryData,
                                  bitcoinSignature: BinaryData) extends RoutingMessage

case class ChannelAnnouncement(nodeSignature1: BinaryData, nodeSignature2: BinaryData,
                               bitcoinSignature1: BinaryData, bitcoinSignature2: BinaryData,
                               features: BinaryData, chainHash: BinaryData, shortChannelId: Long,
                               nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey) extends RoutingMessage {

  val (blockHeight, txIndex, outputIndex) = fromShortId(shortChannelId)
  lazy val nodes = Set(nodeId1, nodeId2)
}

// PAYMENT ROUTE INFO

case class ChannelUpdate(signature: BinaryData, chainHash: BinaryData, shortChannelId: Long,
                         timestamp: Long, messageFlags: Byte, channelFlags: Byte, cltvExpiryDelta: Int,
                         htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long,
                         htlcMaximumMsat: Option[Long] = None) extends RoutingMessage {

  require(requirement = (messageFlags & 1) != 0 == htlcMaximumMsat.isDefined, "htlcMaximumMsat is not consistent with messageFlags")
  def toHop(nodeId: PublicKey) = Hop(nodeId, shortChannelId, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths)
  lazy val feeEstimate = feeBaseMsat + feeProportionalMillionths * 10
}

case class Hop(nodeId: PublicKey, shortChannelId: Long, cltvExpiryDelta: Int,
               htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long) {

  lazy val feeBreakdown = f"${feeProportionalMillionths / 10000D}%2f%% of payment sum + baseline $feeBaseMsat msat"
  lazy val humanDetails = s"Node ID: $nodeId, Channel ID: $shortChannelId, Expiry: $cltvExpiryDelta blocks, Routing fees: $feeBreakdown"
}

// NODE ADDRESS HANDLING

case class NodeAnnouncement(signature: BinaryData, features: BinaryData, timestamp: Long, nodeId: PublicKey,
                            rgbColor: RGB, alias: String, addresses: NodeAddressList) extends RoutingMessage {

  val pretty = addresses collectFirst {
    case _: IPv4 | _: IPv6 => nodeId.toString take 15 grouped 3 mkString "\u0020"
    case _: Tor2 => s"<strong>Tor</strong>\u0020${nodeId.toString take 12 grouped 3 mkString "\u0020"}"
    case _: Tor3 => s"<strong>Tor</strong>\u0020${nodeId.toString take 12 grouped 3 mkString "\u0020"}"
  } getOrElse "No IP address"

  val identifier = (alias + nodeId.toString).toLowerCase
  val asString = s"<strong>${alias take 16}</strong><br><small>$pretty</small>"
}

sealed trait NodeAddress { def canBeUpdatedIfOffline: Boolean }
case object Padding extends NodeAddress { def canBeUpdatedIfOffline = false }
case class IPv4(ipv4: Inet4Address, port: Int) extends NodeAddress { def canBeUpdatedIfOffline = true }
case class IPv6(ipv6: Inet6Address, port: Int) extends NodeAddress { def canBeUpdatedIfOffline = true }
case class Tor2(tor2: String, port: Int) extends NodeAddress { def canBeUpdatedIfOffline = false }
case class Tor3(tor3: String, port: Int) extends NodeAddress { def canBeUpdatedIfOffline = false }

case object NodeAddress {
  val onionSuffix = ".onion"
  val V2Len = 16
  val V3Len = 56

  def onion2Isa(encodedHost: String, port: Int) = encodedHost match {
    case address if address.length == V2Len => new InetSocketAddress(s"$address$onionSuffix", port)
    case address if address.length == V3Len => new InetSocketAddress(s"$address$onionSuffix", port)
    case unknownAddress => throw new RuntimeException(s"Invalid Tor address $unknownAddress")
  }

  def fromParts(host: String, port: Int) =
    if (host.endsWith(onionSuffix) && host.length == V2Len + onionSuffix.length) Tor2(host dropRight onionSuffix.length, port)
    else if (host.endsWith(onionSuffix) && host.length == V3Len + onionSuffix.length) Tor3(host dropRight onionSuffix.length, port)
    else InetAddress getByName host match {
      case ip4Addr: Inet4Address => IPv4(ip4Addr, port)
      case ip6Addr: Inet6Address => IPv6(ip6Addr, port)
    }
}

// Not in a spec
case class WalletZygote(v: Int, db: BinaryData, wallet: BinaryData, chain: BinaryData)
case class CerberusPayload(payloads: Vector[AESZygote], halfTxIds: StringVec)
case class AESZygote(v: Int, iv: BinaryData, ciphertext: BinaryData)