package com.lightning.walletapp.ln.wire

import java.net._
import scodec.codecs._
import java.math.BigInteger
import fr.acinq.eclair.UInt64
import fr.acinq.bitcoin.Crypto
import org.apache.commons.codec.binary.Base32
import com.lightning.walletapp.ln.crypto.Sphinx
import com.lightning.walletapp.ln.{LightningException, RevocationInfo}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import scodec.bits.{BitVector, ByteVector}
import scala.util.{Failure, Success, Try}
import scodec.{Attempt, Codec, Err}


object LightningMessageCodecs { me =>
  type NodeAddressList = List[NodeAddress]
  type BitVectorAttempt = Attempt[BitVector]
  type LNMessageVector = Vector[LightningMessage]
  type RedeemScriptAndSig = (ByteVector, ByteVector)
  type RGB = (Byte, Byte, Byte)

  def serialize(msg: LightningMessage): ByteVector =
    serialize(lightningMessageCodec encode msg)

  def serialize(attempt: BitVectorAttempt) = attempt match {
    case Attempt.Successful(binary) => ByteVector.view(binary.toByteArray)
    case Attempt.Failure(err) => throw new LightningException(err.message)
  }

  def deserialize(raw: ByteVector): LightningMessage =
    lightningMessageCodec decode BitVector(raw) match {
      case Attempt.Successful(decodedResult) => decodedResult.value
      case Attempt.Failure(err) => throw new LightningException(err.message)
    }

  def attemptFromTry[T](run: => T): Attempt[T] = Try(run) match {
    case Failure(reason) => Attempt failure Err(reason.getMessage)
    case Success(result) => Attempt successful result
  }

  // RGB <-> ByteVector
  private val bv2Rgb: PartialFunction[ByteVector, RGB] = {
    case ByteVector(red, green, blue, _*) => (red, green, blue)
  }

  private val rgb2Bv: PartialFunction[RGB, ByteVector] = {
    case (red, green, blue) => ByteVector(red, green, blue)
  }

  def der2wire(signature: ByteVector): ByteVector =
    Crypto decodeSignature signature match { case (r, s) =>
      val fixedR = Crypto fixSize ByteVector.view(r.toByteArray dropWhile 0.==)
      val fixedS = Crypto fixSize ByteVector.view(s.toByteArray dropWhile 0.==)
      fixedR ++ fixedS
    }

  def wire2der(sig: ByteVector): ByteVector = {
    val r = new BigInteger(1, sig.take(32).toArray)
    val s = new BigInteger(1, sig.takeRight(32).toArray)
    Crypto.encodeSignature(r, s) :+ 1.toByte
  }

  // Codecs

  val signature = Codec[ByteVector] (
    encoder = (der: ByteVector) => bytes(64).encode(me der2wire der),
    decoder = (wire: BitVector) => bytes(64).decode(wire).map(_ map wire2der)
  )

  val scalar = Codec[Scalar] (
    encoder = (scalar: Scalar) => bytes(32).encode(scalar.toBin),
    decoder = (wire: BitVector) => bytes(32).decode(wire).map(_ map Scalar.apply)
  )

  val point = Codec[Point] (
    encoder = (point: Point) => bytes(33).encode(point toBin true),
    decoder = (wire: BitVector) => bytes(33).decode(wire).map(_ map Point.apply)
  )

  val publicKey = Codec[PublicKey] (
    encoder = (publicKey: PublicKey) => bytes(33).encode(publicKey.value toBin true),
    decoder = (wire: BitVector) => bytes(33).decode(wire).map(_ map PublicKey.apply)
  )

  val ipv6address: Codec[Inet6Address] = bytes(16).exmap(
    bv => me attemptFromTry Inet6Address.getByAddress(null, bv.toArray, null),
    inet6Address => me attemptFromTry ByteVector.view(inet6Address.getAddress)
  )

  private val ipv4address: Codec[Inet4Address] = bytes(4).xmap(
    bv => InetAddress.getByAddress(bv.toArray).asInstanceOf[Inet4Address],
    inet4Address => ByteVector.view(inet4Address.getAddress)
  )

  def base32(size: Int): Codec[String] = bytes(size).xmap(
    bv => (new Base32 encodeAsString bv.toArray).toLowerCase,
    address => ByteVector.view(new Base32 decode address.toUpperCase)
  )

  def nodeaddress: Codec[NodeAddress] =
    discriminated[NodeAddress].by(uint8)
      .typecase(cr = (ipv4address :: uint16).as[IPv4], tag = 1)
      .typecase(cr = (ipv6address :: uint16).as[IPv6], tag = 2)
      .typecase(cr = (base32(10) :: uint16).as[Tor2], tag = 3)
      .typecase(cr = (base32(35) :: uint16).as[Tor3], tag = 4)

  val uint64: Codec[Long] = int64.narrow(ln => if (ln < 0) Attempt failure Err(s"Overflow $ln") else Attempt successful ln, identity)
  val bytes32: Codec[ByteVector] = limitedSizeBytes(codec = bytesStrict(32).xmap(identity, identity), limit = 32)
  val zeropaddedstring: Codec[String] = fixedSizeBytes(32, utf8).xmap(_.takeWhile(_ != '\u0000'), identity)
  val uint64ex: Codec[UInt64] = bytes(8).xmap(UInt64.apply, _.toByteVector padLeft 8)
  val varsizebinarydataLong: Codec[ByteVector] = variableSizeBytesLong(uint32, bytes)
  val varsizebinarydata: Codec[ByteVector] = variableSizeBytes(uint16, bytes)
  val rgb: Codec[RGB] = bytes(3).xmap(bv2Rgb, rgb2Bv)
  val txvec = vectorOfN(uint16, varsizebinarydata)

  // Data formats

  private val init = (varsizebinarydata withContext "globalFeatures") :: (varsizebinarydata withContext "localFeatures")
  private val error = (bytes32 withContext "channelId") :: (varsizebinarydata withContext "data")
  private val ping = (uint16 withContext "pongLength") :: (varsizebinarydata withContext "data")
  private val pong = varsizebinarydata withContext "data"

  val channelReestablish =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "nextLocalCommitmentNumber") ::
      (uint64 withContext "nextRemoteRevocationNumber") ::
      (optional(bitsRemaining, scalar) withContext "yourLastPerCommitmentSecret") ::
      (optional(bitsRemaining, point) withContext "myCurrentPerCommitmentPoint")

  val channelFlagsCodec =
    (byte withContext "flags").as[ChannelFlags]

  private val openChannel =
    (bytes32 withContext "chainHash") ::
      (bytes32 withContext "temporaryChannelId") ::
      (uint64 withContext "fundingSatoshis") ::
      (uint64 withContext "pushMsat") ::
      (uint64 withContext "dustLimitSatoshis") ::
      (uint64ex withContext "maxHtlcValueInFlightMsat") ::
      (uint64 withContext "channelReserveSatoshis") ::
      (uint64 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeratePerKw") ::
      (uint16 withContext "toSelfDelay") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (publicKey withContext "fundingPubkey") ::
      (point withContext "revocationBasepoint") ::
      (point withContext "paymentBasepoint") ::
      (point withContext "delayedPaymentBasepoint") ::
      (point withContext "htlcBasepoint") ::
      (point withContext "firstPerCommitmentPoint") ::
      (channelFlagsCodec withContext "channelFlags")

  private val acceptChannel =
    (bytes32 withContext "temporaryChannelId") ::
      (uint64 withContext "dustLimitSatoshis") ::
      (uint64ex withContext "maxHtlcValueInFlightMsat") ::
      (uint64 withContext "channelReserveSatoshis") ::
      (uint64 withContext "htlcMinimumMsat") ::
      (uint32 withContext "minimumDepth") ::
      (uint16 withContext "toSelfDelay") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (publicKey withContext "fundingPubkey") ::
      (point withContext "revocationBasepoint") ::
      (point withContext "paymentBasepoint") ::
      (point withContext "delayedPaymentBasepoint") ::
      (point withContext "htlcBasepoint") ::
      (point withContext "firstPerCommitmentPoint")

  val acceptChannelCodec =
    acceptChannel.as[AcceptChannel]

  private val fundingCreated =
    (bytes32 withContext "temporaryChannelId") ::
      (bytes32 withContext "txid") ::
      (uint16 withContext "fundingOutputIndex") ::
      (signature withContext "signature")

  private val fundingSigned =
    (bytes32 withContext "channelId") ::
      (signature withContext "signature")

  private val fundingLocked =
    (bytes32 withContext "channelId" ) ::
      (point withContext "nextPerCommitmentPoint")

  val fundingLockedCodec =
    fundingLocked.as[FundingLocked]

  private val shutdown =
    (bytes32 withContext "channelId") ::
      (varsizebinarydata withContext "scriptPubKey")

  val shutdownCodec =
    shutdown.as[Shutdown]

  private val closingSigned =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "feeSatoshis") ::
      (signature withContext "signature")

  val closingSignedCodec =
    closingSigned.as[ClosingSigned]

  private val updateAddHtlc =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "id") ::
      (uint64 withContext "amountMsat") ::
      (bytes32 withContext "paymentHash") ::
      (uint32 withContext "expiry") ::
      (bytes(Sphinx.PacketLength) withContext "onionRoutingPacket")

  val updateAddHtlcCodec =
    updateAddHtlc.as[UpdateAddHtlc]

  private val updateFulfillHtlc =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "id") ::
      (bytes32 withContext "paymentPreimage")

  val updateFulfillHtlcCodec =
    updateFulfillHtlc.as[UpdateFulfillHtlc]

  private val updateFailHtlc =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "id") ::
      (varsizebinarydata withContext "reason")

  val updateFailHtlcCodec =
    updateFailHtlc.as[UpdateFailHtlc]

  private val updateFailMalformedHtlc =
    (bytes32 withContext "channelId") ::
      (uint64 withContext "id") ::
      (bytes32 withContext "onionHash") ::
      (uint16 withContext "failureCode")

  private val commitSig =
    (bytes32 withContext "channelId") ::
      (signature withContext "signature") ::
      (listOfN(uint16, signature) withContext "htlcSignatures")

  val commitSigCodec =
    commitSig.as[CommitSig]

  private val revokeAndAck =
    (bytes32 withContext "channelId") ::
      (scalar withContext "perCommitmentSecret") ::
      (point withContext "nextPerCommitmentPoint")

  private val updateFee =
    (bytes32 withContext "channelId") ::
      (uint32 withContext "feeratePerKw")

  private val announcementSignatures =
    (bytes32 withContext "channelId") ::
      (int64 withContext "shortChannelId") ::
      (signature withContext "nodeSignature") ::
      (signature withContext "bitcoinSignature")

  val channelAnnouncementWitness =
    (varsizebinarydata withContext "features") ::
      (bytes32 withContext "chainHash") ::
      (int64 withContext "shortChannelId") ::
      (publicKey withContext "nodeId1") ::
      (publicKey withContext "nodeId2") ::
      (publicKey withContext "bitcoinKey1") ::
      (publicKey withContext "bitcoinKey2")

  private val channelAnnouncement =
    (signature withContext "nodeSignature1") ::
      (signature withContext "nodeSignature2") ::
      (signature withContext "bitcoinSignature1") ::
      (signature withContext "bitcoinSignature2") ::
      channelAnnouncementWitness

  val nodeAnnouncementWitness =
    (varsizebinarydata withContext "features") ::
      (uint32 withContext "timestamp") ::
      (publicKey withContext "nodeId") ::
      (rgb withContext "rgbColor") ::
      (zeropaddedstring withContext "alias") ::
      (variableSizeBytes(value = list(nodeaddress), size = uint16) withContext "addresses")

  val channelUpdateWitness =
    (bytes32 withContext "chainHash") ::
      (int64 withContext "shortChannelId") ::
      (uint32 withContext "timestamp") ::
      (byte withContext "messageFlags").flatPrepend { messageFlags =>
        (byte withContext "channelFlags" ) ::
          (uint16 withContext "cltvExpiryDelta") ::
          (uint64 withContext "htlcMinimumMsat") ::
          (uint32 withContext "feeBaseMsat") ::
          (uint32 withContext "feeProportionalMillionths" ) ::
          (conditional(included = (messageFlags & 1) != 0, uint64) withContext "htlcMaximumMsat")
      }

  val nodeAnnouncementCodec = (signature.withContext("signature") :: nodeAnnouncementWitness).as[NodeAnnouncement]
  val channelUpdateCodec = (signature.withContext("signature") :: channelUpdateWitness).as[ChannelUpdate]

  val lightningMessageCodec =
    discriminated[LightningMessage].by(uint16)
      .typecase(cr = init.as[Init], tag = 16)
      .typecase(cr = error.as[Error], tag = 17)
      .typecase(cr = ping.as[Ping], tag = 18)
      .typecase(cr = pong.as[Pong], tag = 19)
      .typecase(cr = openChannel.as[OpenChannel], tag = 32)
      .typecase(cr = acceptChannelCodec, tag = 33)
      .typecase(cr = fundingCreated.as[FundingCreated], tag = 34)
      .typecase(cr = fundingSigned.as[FundingSigned], tag = 35)
      .typecase(cr = fundingLockedCodec, tag = 36)
      .typecase(cr = shutdownCodec, tag = 38)
      .typecase(cr = closingSignedCodec, tag = 39)
      .typecase(cr = updateAddHtlcCodec, tag = 128)
      .typecase(cr = updateFulfillHtlcCodec, tag = 130)
      .typecase(cr = updateFailHtlcCodec, tag = 131)
      .typecase(cr = commitSigCodec, tag = 132)
      .typecase(cr = revokeAndAck.as[RevokeAndAck], tag = 133)
      .typecase(cr = updateFee.as[UpdateFee], tag = 134)
      .typecase(cr = updateFailMalformedHtlc.as[UpdateFailMalformedHtlc], tag = 135)
      .typecase(cr = channelReestablish.as[ChannelReestablish], tag = 136)
      .typecase(cr = channelAnnouncement.as[ChannelAnnouncement], tag = 256)
      .typecase(cr = nodeAnnouncementCodec, tag = 257)
      .typecase(cr = channelUpdateCodec, tag = 258)
      .typecase(cr = announcementSignatures.as[AnnouncementSignatures], tag = 259)

  // Not in a spec

  val hopCodec = {
    (publicKey withContext "nodeId") ::
      (int64 withContext "shortChannelId") ::
      (uint16 withContext "cltvExpiryDelta") ::
      (uint64 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeBaseMsat") ::
      (uint32 withContext "feeProportionalMillionths")
  }.as[Hop]

  val revocationInfoCodec = {
    (listOfN(uint16, varsizebinarydata ~ signature) withContext "redeemScriptsToSigs") ::
      (optional(bool, signature) withContext "claimMainTxSig") ::
      (optional(bool, signature) withContext "claimPenaltyTxSig") ::
      (uint64 withContext "feeRate") ::
      (uint64 withContext "dustLimit") ::
      (varsizebinarydata withContext "finalScriptPubKey") ::
      (uint16 withContext "toSelfDelay") ::
      (publicKey withContext "localPubKey") ::
      (publicKey withContext "remoteRevocationPubkey") ::
      (publicKey withContext "remoteDelayedPaymentKey")
  }.as[RevocationInfo]

  val aesZygoteCodec = {
    (uint16 withContext "v") ::
      (varsizebinarydataLong withContext "iv") ::
      (varsizebinarydataLong withContext "ciphertext")
  }.as[AESZygote]

  val cerberusPayloadCodec = {
    (vectorOfN(uint16, aesZygoteCodec) withContext "payloads") ::
      (vectorOfN(uint16, zeropaddedstring) withContext "halfTxIds")
  }.as[CerberusPayload]
}