package com.lightning.wallet.ln.crypto

import com.lightning.wallet.ln.crypto.Sphinx._
import com.lightning.wallet.ln.crypto.MultiStreamUtils._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, Scalar}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import fr.acinq.bitcoin.{Crypto, Protocol}

import com.lightning.wallet.ln.wire.FailureMessageCodecs.failureMessageCodec
import com.lightning.wallet.ln.wire.FailureMessage
import com.lightning.wallet.ln.LightningException
import com.lightning.wallet.ln.Tools.Bytes
import org.bitcoinj.core.Sha256Hash
import scodec.bits.BitVector
import java.math.BigInteger
import java.nio.ByteOrder


case class Packet(v: Bytes, publicKey: Bytes, routingInfo: Bytes, hmac: Bytes) { me =>
  require(hmac.length == MacLength, s"Onion header hmac length should be exactly $MacLength bytes")
  require(publicKey.length == 33, "Onion header public key length should be exactly 33 bytes")
  require(routingInfo.length == DataLength, "Invalid routing info length")
  def isLast: Boolean = hmac sameElements zeroes(MacLength)
  def serialize: Bytes = Packet write me
}

object Packet { me =>
  def read(in: Bytes): Packet = me read new ByteArrayInputStream(in)
  def write(packet: Packet): Bytes = write(new ByteArrayOutputStream(PacketLength), packet)
  def write(out: ByteArrayOutputStream, header: Packet): Bytes = awrite(out, header.v,
    header.publicKey, header.routingInfo, header.hmac).toByteArray

  private def read(stream: ByteArrayInputStream) = {
    val res = aread(stream, 1, 33, DataLength, MacLength)
    val Seq(version, publicKey, routingInfo, hmac) = res
    Packet(version, publicKey, routingInfo, hmac)
  }
}

case class ParsedPacket(payload: Bytes, nextPacket: Packet, sharedSecret: Bytes)
case class SecretsAndPacket(sharedSecrets: Vector[BytesAndKey], packet: Packet)
case class ErrorPacket(originNode: PublicKey, failureMessage: FailureMessage)

object Sphinx { me =>
  type BytesVec = Vector[Bytes]
  type PublicKeyVec = Vector[PublicKey]
  type BytesAndKey = (Bytes, PublicKey)

  val Version = 0.toByte
  val PayloadLength = 33
  val MacLength = 32
  val MaxHops = 20

  /*
    error packet format:
    +----------------+----------------------------------+-----------------+----------------------+-----+
    | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
    +----------------+----------------------------------+-----------------+----------------------+-----+
    with failure message length + pad length = 256
  */

  val MaxErrorPayloadLength = 256
  val ErrorPacketLength = MacLength + MaxErrorPayloadLength + 2 + 2
  val DataLength = (PayloadLength + MacLength) * MaxHops
  val PacketLength = 1 + 33 + MacLength + DataLength

  val LAST_ADDRESS = zeroes(PayloadLength)
  val LAST_PACKET = Packet(Array(Version), zeroes(33), me zeroes DataLength, me zeroes MacLength)
  def mac(key: Bytes, message: Bytes): Bytes = Digests.hmacSha256(key, message) take MacLength
  def xor(a: Bytes, b: Bytes): Bytes = a zip b map { case (x, y) => x.^(y).&(0xFF).toByte }
  def zeroes(length: Int): Bytes = Array.fill[Byte](length)(0)

  def generateKey(keyType: Bytes, secret: Bytes): Bytes = Digests.hmacSha256(keyType, secret)
  def generateKey(keyType: String, secret: Bytes): Bytes = generateKey(keyType getBytes "UTF-8", secret)
  def computeblindingFactor(pub: PublicKey, secret: Bytes): Bytes = Sha256Hash hash aconcat(pub.toBin, secret)
  def blind(pub: PublicKey, blindingFactors: BytesVec): PublicKey = blindingFactors.foldLeft(pub)(blind)

  def blind(pub: PublicKey, blindingFactor: Bytes): PublicKey = {
    val scalar = Scalar apply new BigInteger(1, blindingFactor)
    PublicKey(pub.multiply(scalar).normalize)
  }

  def computeSharedSecret(pub: PublicKey, secret: PrivateKey): Bytes =
    Sha256Hash.hash(pub.multiply(secret).normalize getEncoded true)

  def generateStream(key: Bytes, length: Int): Bytes =
    ChaCha20Legacy.process(me zeroes length, key, me zeroes 8,
      encrypt = true, skipBlock = false)

  def computeEphemerealPublicKeysAndSharedSecrets(publicKeys: PublicKeyVec, sessionKey: PrivateKey): (PublicKeyVec, BytesVec) = {
    val firstEphemerealPublicKey = blind(pub = PublicKey(value = Crypto.curve.getG, compressed = true), sessionKey.value.toByteArray)
    val firstSecret: Bytes = computeSharedSecret(pub = publicKeys.head, secret = sessionKey)
    val firstBlindingFactor = computeblindingFactor(firstEphemerealPublicKey, firstSecret)

    computeEphemerealPublicKeysAndSharedSecrets(publicKeys.tail,
      Vector(firstEphemerealPublicKey), Vector(firstBlindingFactor),
      Vector(firstSecret), sessionKey)
  }

  def computeEphemerealPublicKeysAndSharedSecrets(publicKeys: PublicKeyVec, ephemerealPublicKeys: PublicKeyVec,
                                                  blindingFactors: BytesVec, sharedSecrets: BytesVec,
                                                  sessionKey: PrivateKey): (PublicKeyVec, BytesVec) =

    if (publicKeys.isEmpty) (ephemerealPublicKeys, sharedSecrets) else {
      val nextEphemerealPublicKey = blind(ephemerealPublicKeys.last, blindingFactors.last)
      val nextSecret = computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      computeEphemerealPublicKeysAndSharedSecrets(publicKeys.tail, ephemerealPublicKeys :+ nextEphemerealPublicKey,
        blindingFactors :+ computeblindingFactor(nextEphemerealPublicKey, nextSecret), sharedSecrets :+ nextSecret, sessionKey)
    }

  def generateFiller(keyType: String, sharedSecrets: BytesVec, hopSize: Int, maxHops: Int): Bytes =
    sharedSecrets.foldLeft(Array.emptyByteArray) { case (paddingAccumulator: Bytes, secret: Bytes) =>
      val stream = generateStream(generateKey(keyType, secret), (maxHops + 1) * hopSize)
      val pad: Bytes = aconcat(paddingAccumulator, me zeroes hopSize)
      xor(pad, stream takeRight pad.length)
    }

  def parsePacket(privateKey: PrivateKey, associatedData: Bytes, rawPacket: Bytes): ParsedPacket = {
    require(rawPacket.length == PacketLength, s"Onion packet length is ${rawPacket.length}, it should be $PacketLength")

    val packet = Packet read rawPacket
    val message = aconcat(packet.routingInfo, associatedData)
    val sharedSecret = computeSharedSecret(PublicKey(packet.publicKey), privateKey)
    require(mac(generateKey("mu", sharedSecret), message) sameElements packet.hmac, "Invalid header mac")
    val stream1 = generateStream(generateKey("rho", sharedSecret), PayloadLength + MacLength + DataLength)
    val bin = xor(aconcat(packet.routingInfo, me zeroes PayloadLength + MacLength), stream1)
    val hmac = bin.slice(PayloadLength, PayloadLength + MacLength)
    val nextRoutinfo = bin.drop(PayloadLength + MacLength)

    val factor = computeblindingFactor(PublicKey(packet.publicKey), sharedSecret)
    val pubKey = blind(PublicKey(packet.publicKey), factor).toBin.toArray
    val nextPacket = Packet(Array(Version), pubKey, nextRoutinfo, hmac)
    ParsedPacket(bin take PayloadLength, nextPacket, sharedSecret)
  }


  def makeNextPacket(payload: Bytes, associatedData: Bytes, ephemerealPublicKey: Bytes,
                     sharedSecret: Bytes, packet: Packet, routingInfoFiller: Bytes) = {

    val nextRoutingInfo = {
      val stream1 = generateStream(generateKey("rho", sharedSecret), DataLength)
      val routingInfo1 = aconcat(payload, packet.hmac, packet.routingInfo dropRight PayloadLength + MacLength)
      aconcat(xor(routingInfo1, stream1).dropRight(routingInfoFiller.length), routingInfoFiller)
    }

    require(payload.length == PayloadLength)
    val message = aconcat(nextRoutingInfo, associatedData)
    val nextHmac = mac(generateKey("mu", sharedSecret), message)
    Packet(Array(Version), ephemerealPublicKey, nextRoutingInfo, nextHmac)
  }

  // Builds an encrypted onion packet that contains payloads and routing information for all nodes
  // Returns an onion packet that can be sent to the first node in the list

  def makePacket(seskey: PrivateKey, pubKeys: PublicKeyVec, payloads: BytesVec, assocData: Bytes) = {
    def loop(packet: Packet, hopPayloads: BytesVec, ephemeralKeys: PublicKeyVec, sharedSecrets: BytesVec): Packet =
      if (hopPayloads.isEmpty) packet else loop(makeNextPacket(hopPayloads.last, assocData, ephemeralKeys.last.toBin,
        sharedSecrets.last, packet, Array.emptyByteArray), hopPayloads dropRight 1, ephemeralKeys dropRight 1,
        sharedSecrets dropRight 1)

    val (ephemerealPublicKeys, sharedsecrets) = computeEphemerealPublicKeysAndSharedSecrets(pubKeys, seskey)
    val filler = generateFiller(keyType = "rho", sharedsecrets dropRight 1, hopSize = PayloadLength + MacLength, maxHops = MaxHops)
    val lastPacket = makeNextPacket(payloads.last, assocData, ephemerealPublicKeys.last.toBin, sharedsecrets.last, LAST_PACKET, filler)
    val packet = loop(lastPacket, payloads dropRight 1, ephemerealPublicKeys dropRight 1, sharedsecrets dropRight 1)
    SecretsAndPacket(sharedsecrets zip pubKeys, packet)
  }

  // Error packets

  def createErrorPacket(sharedSecret: Bytes, failure: FailureMessage) = {
    val message: Bytes = failureMessageCodec.encode(failure).require.toByteArray
    if (message.length > MaxErrorPayloadLength) throw new LightningException

    val payload = aconcat(Protocol.writeUInt16(message.length, ByteOrder.BIG_ENDIAN),
      message, Protocol.writeUInt16(MaxErrorPayloadLength - message.length, ByteOrder.BIG_ENDIAN),
        me zeroes MaxErrorPayloadLength - message.length)

    val mac1 = mac(generateKey("um", sharedSecret), payload)
    forwardErrorPacket(aconcat(mac1, payload), sharedSecret)
  }

  def extractFailureMessage(packet: Bytes) =
    packet drop MacLength match { case payload =>
      if (packet.length != ErrorPacketLength) throw new LightningException
      val msg = payload.slice(2, Protocol.uint16(payload, ByteOrder.BIG_ENDIAN) + 2)
      failureMessageCodec.decode(BitVector apply msg).require.value
    }

  def forwardErrorPacket(packet: Bytes, sharedSecret: Bytes) = {
    if (packet.length != ErrorPacketLength) throw new LightningException
    val stream = generateStream(generateKey("ammag", sharedSecret), ErrorPacketLength)
    xor(packet, stream)
  }

  // May throw if shared secrets is empty
  def parseErrorPacket(secrets: Vector[BytesAndKey],
                       packet: Bytes): ErrorPacket = {

    val (secret, pubkey) = secrets.head
    val packet1 = forwardErrorPacket(packet, secret)
    val (providedMac, payload) = packet1 splitAt MacLength
    val macCheck = mac(generateKey("um", secret), payload)

    if (providedMac sameElements macCheck) {
      val failureMessage = extractFailureMessage(packet1)
      ErrorPacket(originNode = pubkey, failureMessage)
    } else parseErrorPacket(secrets.tail, packet1)
  }
}