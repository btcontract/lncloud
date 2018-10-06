package com.lightning.walletapp.ln.wire

import java.net._
import scodec.codecs._
import java.math.BigInteger
import fr.acinq.eclair.UInt64
import com.lightning.walletapp.ln.crypto.Sphinx
import com.lightning.walletapp.ln.{LightningException, RevocationInfo}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import scodec.bits.{BitVector, ByteVector}
import scala.util.{Failure, Success, Try}
import scodec.{Attempt, Codec, Err}


object LightningMessageCodecs { me =>
  type NodeAddressList = List[NodeAddress]
  type BitVectorAttempt = Attempt[BitVector]
  type LNMessageVector = Vector[LightningMessage]
  type RedeemScriptAndSig = (BinaryData, BinaryData)
  type RGB = (Byte, Byte, Byte)

  def serialize(msg: LightningMessage): BinaryData =
    serialize(lightningMessageCodec encode msg)

  def serialize(attempt: BitVectorAttempt) = attempt match {
    case Attempt.Successful(binary) => BinaryData(binary.toByteArray)
    case Attempt.Failure(err) => throw new LightningException(err.message)
  }

  def deserialize(raw: BinaryData): LightningMessage =
    lightningMessageCodec decode BitVector(raw.data) match {
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

  // BinaryData <-> ByteVector
  val vec2Bin = (vec: ByteVector) => BinaryData(vec.toArray)
  val bin2Vec = (bin: BinaryData) => ByteVector(bin.data)

  def der2wire(signature: BinaryData): BinaryData =
    Crypto decodeSignature signature match { case (r, s) =>
      val fixedR = Crypto fixSize r.toByteArray.dropWhile(0.==)
      val fixedS = Crypto fixSize s.toByteArray.dropWhile(0.==)
      fixedR ++ fixedS
    }

  def wire2der(sig: BinaryData): BinaryData = {
    val r = new BigInteger(1, sig.take(32).toArray)
    val s = new BigInteger(1, sig.takeRight(32).toArray)
    Crypto.encodeSignature(r, s) :+ 1.toByte
  }

  // Codecs

  val signature = Codec[BinaryData] (
    encoder = (der: BinaryData) => bytes(64) encode bin2Vec(me der2wire der),
    decoder = (wire: BitVector) => bytes(64).decode(wire).map(_ map vec2Bin map wire2der)
  )

  val scalar = Codec[Scalar] (
    encoder = (scalar: Scalar) => bytes(32) encode bin2Vec(scalar.toBin),
    decoder = (wire: BitVector) => bytes(32).decode(wire).map(_ map vec2Bin map Scalar.apply)
  )

  val point = Codec[Point] (
    encoder = (point: Point) => bytes(33) encode bin2Vec(point toBin true),
    decoder = (wire: BitVector) => bytes(33).decode(wire).map(_ map vec2Bin map Point.apply)
  )

  val publicKey = Codec[PublicKey] (
    encoder = (publicKey: PublicKey) => bytes(33) encode bin2Vec(publicKey.value toBin true),
    decoder = (wire: BitVector) => bytes(33).decode(wire).map(_ map vec2Bin map PublicKey.apply)
  )

  val ipv6address: Codec[Inet6Address] = bytes(16).exmap(
    bv => me attemptFromTry Inet6Address.getByAddress(null, bv.toArray, null),
    inet6Address => me attemptFromTry ByteVector(inet6Address.getAddress)
  )

  private val ipv4address: Codec[Inet4Address] = bytes(4).xmap(
    bv => InetAddress.getByAddress(bv.toArray).asInstanceOf[Inet4Address],
    inet4Address => ByteVector(inet4Address.getAddress)
  )

  val nodeaddress: Codec[NodeAddress] =
    discriminated[NodeAddress].by(uint8)
      .typecase(cr = provide(Padding), tag = 0)
      .typecase(cr = (ipv4address ~ uint16).xmap[IPv4](IPv4.tupled, x => x.ipv4 -> x.port), tag = 1)
      .typecase(cr = (ipv6address ~ uint16).xmap[IPv6](IPv6.tupled, x => x.ipv6 -> x.port), tag = 2)
      .typecase(cr = (binarydata(10) ~ uint16).xmap[Tor2](Tor2.tupled, x => x.tor2 -> x.port), tag = 3)
      .typecase(cr = (binarydata(35) ~ uint16).xmap[Tor3](Tor3.tupled, x => x.tor3 -> x.port), tag = 4)

  def binarydata(size: Int): Codec[BinaryData] = bytes(size).xmap(vec2Bin, bin2Vec)
  val varsizebinarydata: Codec[BinaryData] = variableSizeBytes(value = bytes.xmap(vec2Bin, bin2Vec), size = uint16)
  val varsizebinarydataLong: Codec[BinaryData] = variableSizeBytesLong(value = bytes.xmap(vec2Bin, bin2Vec), size = uint32)
  val uint64ex: Codec[UInt64] = bytes(8).xmap(b => UInt64(b.toArray), a => ByteVector(a.underlying.toByteArray) takeRight 8 padLeft 8)
  val uint64: Codec[Long] = int64.narrow(ln => if (ln < 0) Attempt failure Err(s"Overflow $ln") else Attempt successful ln, identity)
  val zeropaddedstring: Codec[String] = fixedSizeBytes(32, utf8).xmap(_.takeWhile(_ != '\u0000'), identity)
  val rgb: Codec[RGB] = bytes(3).xmap(bv2Rgb, rgb2Bv)

  // Data formats

  private val init =
    (varsizebinarydata withContext "globalFeatures") ::
      (varsizebinarydata withContext "localFeatures")

  private val error =
    (binarydata(32) withContext "channelId") ::
      (varsizebinarydata withContext "data")

  private val ping =
    (uint16 withContext "pongLength") ::
      (varsizebinarydata withContext "data")

  private val pong =
    varsizebinarydata withContext "data"

  val channelReestablish =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "nextLocalCommitmentNumber") ::
      (uint64 withContext "nextRemoteRevocationNumber") ::
      (optional(bitsRemaining, scalar) withContext "yourLastPerCommitmentSecret") ::
      (optional(bitsRemaining, point) withContext "myCurrentPerCommitmentPoint")

  private val openChannel =
    (binarydata(32) withContext "chainHash") ::
      (binarydata(32) withContext "temporaryChannelId") ::
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
      (byte withContext "channelFlags")

  private val acceptChannel =
    (binarydata(32) withContext "temporaryChannelId") ::
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

  val acceptChannelCodec: Codec[AcceptChannel] =
    acceptChannel.as[AcceptChannel]

  private val fundingCreated =
    (binarydata(32) withContext "temporaryChannelId") ::
      (binarydata(32) withContext "txid") ::
      (uint16 withContext "fundingOutputIndex") ::
      (signature withContext "signature")

  private val fundingSigned =
    (binarydata(32) withContext "channelId") ::
      (signature withContext "signature")

  private val fundingLocked =
    (binarydata(32) withContext "channelId" ) ::
      (point withContext "nextPerCommitmentPoint")

  val fundingLockedCodec: Codec[FundingLocked] =
    fundingLocked.as[FundingLocked]

  private val shutdown =
    (binarydata(32) withContext "channelId") ::
      (varsizebinarydata withContext "scriptPubKey")

  val shutdownCodec: Codec[Shutdown] =
    shutdown.as[Shutdown]

  private val closingSigned =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "feeSatoshis") ::
      (signature withContext "signature")

  val closingSignedCodec: Codec[ClosingSigned] =
    closingSigned.as[ClosingSigned]

  private val updateAddHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (uint64 withContext "amountMsat") ::
      (binarydata(32) withContext "paymentHash") ::
      (uint32 withContext "expiry") ::
      (binarydata(Sphinx.PacketLength) withContext "onionRoutingPacket")

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] =
    updateAddHtlc.as[UpdateAddHtlc]

  private val updateFulfillHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (binarydata(32) withContext "paymentPreimage")

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] =
    updateFulfillHtlc.as[UpdateFulfillHtlc]

  private val updateFailHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (varsizebinarydata withContext "reason")

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] =
    updateFailHtlc.as[UpdateFailHtlc]

  private val updateFailMalformedHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (binarydata(32) withContext "onionHash") ::
      (uint16 withContext "failureCode")

  private val commitSig =
    (binarydata(32) withContext "channelId") ::
      (signature withContext "signature") ::
      (listOfN(uint16, signature) withContext "htlcSignatures")

  val commitSigCodec: Codec[CommitSig] =
    commitSig.as[CommitSig]

  private val revokeAndAck =
    (binarydata(32) withContext "channelId") ::
      (scalar withContext "perCommitmentSecret") ::
      (point withContext "nextPerCommitmentPoint")

  private val updateFee =
    (binarydata(32) withContext "channelId") ::
      (uint32 withContext "feeratePerKw")

  private val announcementSignatures =
    (binarydata(32) withContext "channelId") ::
      (int64 withContext "shortChannelId") ::
      (signature withContext "nodeSignature") ::
      (signature withContext "bitcoinSignature")

  val channelAnnouncementWitness =
    (varsizebinarydata withContext "features") ::
      (binarydata(32) withContext "chainHash") ::
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
    (binarydata(32) withContext "chainHash") ::
      (int64 withContext "shortChannelId") ::
      (uint32 withContext "timestamp") ::
      (binarydata(2) withContext "flags") ::
      (uint16 withContext "cltvExpiryDelta") ::
      (uint64 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeBaseMsat") ::
      (uint32 withContext "feeProportionalMillionths")

  private val hop =
    (publicKey withContext "nodeId") ::
      (int64 withContext "shortChannelId") ::
      (uint16 withContext "cltvExpiryDelta") ::
      (uint64 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeBaseMsat") ::
      (uint32 withContext "feeProportionalMillionths")

  private val channelUpdate = (signature withContext "signature") :: channelUpdateWitness
  private val nodeAnnouncement = (signature withContext "signature") :: nodeAnnouncementWitness
  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = nodeAnnouncement.as[NodeAnnouncement]
  val channelUpdateCodec: Codec[ChannelUpdate] = channelUpdate.as[ChannelUpdate]
  val hopCodec: Codec[Hop] = hop.as[Hop]

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

  private val revocationInfo =
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

  private val walletZygote =
    (uint16 withContext "v") ::
      (varsizebinarydataLong withContext "db") ::
      (varsizebinarydataLong withContext "wallet") ::
      (varsizebinarydataLong withContext "chain")

  private val aesZygote =
    (uint16 withContext "v") ::
      (varsizebinarydataLong withContext "iv") ::
      (varsizebinarydataLong withContext "ciphertext")

  val aesZygoteCodec = aesZygote.as[AESZygote]

  private val cerberusPayload =
    (vectorOfN(uint16, aesZygoteCodec) withContext "payloads") ::
      (vectorOfN(uint16, utf8) withContext "halfTxIds")

  val walletZygoteCodec = walletZygote.as[WalletZygote]
  val revocationInfoCodec = revocationInfo.as[RevocationInfo]
  val cerberusPayloadCodec = cerberusPayload.as[CerberusPayload]
}