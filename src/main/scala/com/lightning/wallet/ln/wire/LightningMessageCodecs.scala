package com.lightning.wallet.ln.wire

import java.net._
import scodec.codecs._
import com.lightning.wallet.ln.Exceptions._

import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec, Err}

import com.lightning.wallet.ln.crypto.Sphinx
import java.math.BigInteger


object LightningMessageCodecs { me =>
  type BitVectorAttempt = Attempt[BitVector]
  type InetSocketAddressList = List[InetSocketAddress]
  type NodeAnnouncements = Vector[NodeAnnouncement]
  type PaymentRoute = Vector[Hop]

  type AddressPort = (InetAddress, Int)
  type RGB = (Byte, Byte, Byte)

  def serializationResult(attempt: BitVectorAttempt): BinaryData = attempt match {
    case Attempt.Failure(some) => throw DetailedException(SERIALIZATION_ERROR, some)
    case Attempt.Successful(bin) => BinaryData(bin.toByteArray)
  }

  def deserializationResult(transferred: BinaryData) =
    lightningMessageCodec decode BitVector(transferred.data) match {
      case Attempt.Failure(_) => throw ChannelException(DESERIALIZATION_ERROR)
      case Attempt.Successful(result) => result.value
    }

  // RGB <-> ByteVector
  private val bv2Rgb: PartialFunction[ByteVector, RGB] = {
    case ByteVector(red, green, blue, _*) => (red, green, blue)
  }

  private val rgb2Bv: PartialFunction[RGB, ByteVector] = {
    case (red, green, blue) => ByteVector(red, green, blue)
  }

  // BinaryData <-> ByteVector
  private val vec2Bin = (vec: ByteVector) => BinaryData(vec.toArray)
  private val bin2Vec = (bin: BinaryData) => ByteVector(bin.data)

  // IP v4/6 <-> ByteVector
  private val inet2Bv = (a: InetAddress) => ByteVector(a.getAddress)
  private val bv2Inet6 = (bv: ByteVector) => InetAddress.getByAddress(bv.toArray).asInstanceOf[Inet6Address]
  private val bv2Inet4 = (bv: ByteVector) => InetAddress.getByAddress(bv.toArray).asInstanceOf[Inet4Address]

  // DER encoded <-> wire encoded
  def fixSize(data: BinaryData): BinaryData = data.length match {
    case length if length < 32 => Array.fill(32 - length)(0.toByte) ++ data
    case 32 => data
  }

  def der2wire(signature: BinaryData): BinaryData =
    Crypto decodeSignature signature match { case (r, s) =>
      val fixedR = me fixSize r.toByteArray.dropWhile(0.==)
      val fixedS = me fixSize s.toByteArray.dropWhile(0.==)
      fixedR ++ fixedS
    }

  def wire2der(sig: BinaryData): BinaryData = {
    val r = new BigInteger(1, sig.take(32).toArray)
    val s = new BigInteger(1, sig.takeRight(32).toArray)
    Crypto.encodeSignature(r, s) :+ 1.toByte
  }

  // Codecs

  val signature = Codec[BinaryData](
    encoder = (derEncoded: BinaryData) => {
      val vec = bin2Vec(me der2wire derEncoded)
      bytes(64) encode vec
    },

    decoder = (wireEncoded: BitVector) => for {
      decodeResult <- bytes(64) decode wireEncoded
    } yield decodeResult map vec2Bin map wire2der
  )

  val scalar = Codec[Scalar](
    encoder = (scalar: Scalar) => {
      val vec = bin2Vec(scalar.toBin)
      bytes(32) encode vec
    },

    decoder = (wireEncoded: BitVector) => for {
      decodeResult <- bytes(32) decode wireEncoded
    } yield decodeResult map vec2Bin map Scalar.apply
  )

  val point = Codec[Point](
    encoder = (point: Point) => {
      val vec = bin2Vec(point toBin true)
      bytes(33) encode vec
    },

    decoder = (wireEncoded: BitVector) => for {
      decodeResult <- bytes(33) decode wireEncoded
    } yield decodeResult map vec2Bin map Point.apply
  )

  val publicKey = Codec[PublicKey](
    encoder = (publicKey: PublicKey) => {
      val vec = bin2Vec(publicKey.value toBin true)
      bytes(33) encode vec
    },

    decoder = (wireEncoded: BitVector) => for {
      decodeResult <- bytes(33) decode wireEncoded
    } yield decodeResult map vec2Bin map PublicKey.apply
  )

  private val uint64: Codec[Long] = int64.narrow(long =>
    if (long < 0) Attempt failure Err(s"Overflow for $long")
    else Attempt successful long, identity)

  private val ipv6address: Codec[Inet6Address] = bytes(16).xmap(bv2Inet6, inet2Bv)
  private val ipv4address: Codec[Inet4Address] = bytes(4).xmap(bv2Inet4, inet2Bv)

  private val addressport: Codec[AddressPort] = discriminated[InetAddress]
    .by(uint8).typecase(1, ipv4address).typecase(2, ipv6address) ~ uint16

  val socketaddress: Codec[InetSocketAddress] = addressport.xmap(
    addressAndPort => new InetSocketAddress(addressAndPort._1, addressAndPort._2),
    inetSockAddress => (inetSockAddress.getAddress, inetSockAddress.getPort)
  )

  val rgb: Codec[RGB] = bytes(3).xmap(bv2Rgb, rgb2Bv)
  def binarydata(size: Int): Codec[BinaryData] = bytes(size).xmap(vec2Bin, bin2Vec)
  val varsizebinarydata: Codec[BinaryData] = variableSizeBytesLong(value = bytes.xmap(vec2Bin, bin2Vec), size = uint32)
  val zeropaddedstring: Codec[String] = fixedSizeBytes(32, utf8).xmap(_.takeWhile(_ != '\u0000'), identity)

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

  private val openChannel =
    (binarydata(32) withContext "chainHash") ::
      (binarydata(32) withContext "temporaryChannelId") ::
      (uint64 withContext "fundingSatoshis") ::
      (uint64 withContext "pushMsat") ::
      (uint64 withContext "dustLimitSatoshis") ::
      (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (uint64 withContext "channelReserveSatoshis") ::
      (uint32 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeratePerKw") ::
      (uint16 withContext "toSelfDelay") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (publicKey withContext "fundingPubkey") ::
      (point withContext "revocationBasepoint") ::
      (point withContext "paymentBasepoint") ::
      (point withContext "delayedPaymentBasepoint") ::
      (point withContext "firstPerCommitmentPoint")

  private val acceptChannel =
    (binarydata(32) withContext "temporaryChannelId") ::
      (uint64 withContext "dustLimitSatoshis") ::
      (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (uint64 withContext "channelReserveSatoshis") ::
      (uint32 withContext "minimumDepth") ::
      (uint32 withContext "htlcMinimumMsat") ::
      (uint16 withContext "toSelfDelay") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (publicKey withContext "fundingPubkey") ::
      (point withContext "revocationBasepoint") ::
      (point withContext "paymentBasepoint") ::
      (point withContext "delayedPaymentBasepoint") ::
      (point withContext "firstPerCommitmentPoint")

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

  private val shutdown =
    (binarydata(32) withContext "channelId") ::
      (varsizebinarydata withContext "scriptPubKey")

  private val closingSigned =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "feeSatoshis") ::
      (signature withContext "signature")

  private val updateAddHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (uint32 withContext "amountMsat") ::
      (uint32 withContext "expiry") ::
      (binarydata(32) withContext "paymentHash") ::
      (binarydata(Sphinx.PacketLength) withContext "onionRoutingPacket")

  private val updateFulfillHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (binarydata(32) withContext "paymentPreimage")

  private val updateFailHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (varsizebinarydata withContext "reason")

  private val updateFailMalformedHtlc =
    (binarydata(32) withContext "channelId") ::
      (uint64 withContext "id") ::
      (binarydata(32) withContext "onionHash") ::
      (uint16 withContext "failureCode")

  private val commitSig =
    (binarydata(32) withContext "channelId") ::
      (signature withContext "signature") ::
      (listOfN(uint16, signature) withContext "htlcSignatures")

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
    (int64 withContext "shortChannelId") ::
      (binarydata(33) withContext "nodeId1") ::
      (binarydata(33) withContext "nodeId2") ::
      (binarydata(33) withContext "bitcoinKey1") ::
      (binarydata(33) withContext "bitcoinKey2") ::
      (varsizebinarydata withContext "features")

  private val channelAnnouncement =
    (signature withContext "nodeSignature1") ::
      (signature withContext "nodeSignature2") ::
      (signature withContext "bitcoinSignature1") ::
      (signature withContext "bitcoinSignature2") ::
      channelAnnouncementWitness

  val nodeAnnouncementWitness =
    (uint32 withContext "timestamp") ::
      (binarydata(33) withContext "nodeId") ::
      (rgb withContext "rgbColor") ::
      (zeropaddedstring withContext "alias") ::
      (varsizebinarydata withContext "features") ::
      (listOfN(uint16, socketaddress) withContext "addresses")

  private val nodeAnnouncement =
    (signature withContext "signature") ::
      nodeAnnouncementWitness

  val channelUpdateWitness =
    (int64 withContext "shortChannelId") ::
      (uint32 withContext "timestamp") ::
      (binarydata(2) withContext "flags") ::
      (uint16 withContext "cltvExpiryDelta") ::
      (uint32 withContext "htlcMinimumMsat") ::
      (uint32 withContext "feeBaseMsat") ::
      (uint32 withContext "feeProportionalMillionths")

  private val channelUpdate =
    (signature withContext "signature") ::
      channelUpdateWitness

  val channelUpdateCodec: Codec[ChannelUpdate] = channelUpdate.as[ChannelUpdate]
  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = nodeAnnouncement.as[NodeAnnouncement]

  private val hop =
    (publicKey withContext "nodeId") ::
      (publicKey withContext "nextNodeId") ::
      (channelUpdateCodec withContext "lastUpdate")

  val perHopPayload =
    (constant(ByteVector fromByte 0) withContext "realm") ::
      (uint64 withContext "channel_id") ::
      (uint32 withContext "amt_to_forward") ::
      (int32 withContext "outgoing_cltv_value") ::
      (ignore(8 * 16) withContext "unused_with_v0_version_on_header")

  val hopsCodec: Codec[PaymentRoute] = vectorOfN(valueCodec = hop.as[Hop], countCodec = uint16)
  val perHopPayloadCodec: Codec[PerHopPayload] = perHopPayload.as[PerHopPayload]

  val lightningMessageCodec =
    discriminated[LightningMessage].by(uint16)
      .typecase(cr = init.as[Init], tag = 16)
      .typecase(cr = error.as[Error], tag = 17)
      .typecase(cr = ping.as[Ping], tag = 18)
      .typecase(cr = pong.as[Pong], tag = 19)
      .typecase(cr = openChannel.as[OpenChannel], tag = 32)
      .typecase(cr = acceptChannel.as[AcceptChannel], tag = 33)
      .typecase(cr = fundingCreated.as[FundingCreated], tag = 34)
      .typecase(cr = fundingSigned.as[FundingSigned], tag = 35)
      .typecase(cr = fundingLocked.as[FundingLocked], tag = 36)
      .typecase(cr = shutdown.as[Shutdown], tag = 38)
      .typecase(cr = closingSigned.as[ClosingSigned], tag = 39)
      .typecase(cr = updateAddHtlc.as[UpdateAddHtlc], tag = 128)
      .typecase(cr = updateFulfillHtlc.as[UpdateFulfillHtlc], tag = 130)
      .typecase(cr = updateFailHtlc.as[UpdateFailHtlc], tag = 131)
      .typecase(cr = commitSig.as[CommitSig], tag = 132)
      .typecase(cr = revokeAndAck.as[RevokeAndAck], tag = 133)
      .typecase(cr = updateFee.as[UpdateFee], tag = 134)
      .typecase(cr = updateFailMalformedHtlc.as[UpdateFailMalformedHtlc], tag = 135)
      .typecase(cr = channelAnnouncement.as[ChannelAnnouncement], tag = 256)
      .typecase(cr = nodeAnnouncementCodec, tag = 257)
      .typecase(cr = channelUpdateCodec, tag = 258)
      .typecase(cr = announcementSignatures.as[AnnouncementSignatures], tag = 259)
}