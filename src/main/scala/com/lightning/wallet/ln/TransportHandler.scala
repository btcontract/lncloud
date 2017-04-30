package com.lightning.wallet.ln

import TransportHandler._
import com.lightning.wallet.ln.crypto.Noise._
import fr.acinq.bitcoin.{BinaryData, Protocol}
import com.lightning.wallet.ln.Tools.random
import java.nio.ByteOrder


trait DataTransport { def send(data: BinaryData): Unit }
class TransportHandler(keyPair: KeyPair, rs: Option[BinaryData], consume: BinaryData => Unit,
                       transport: DataTransport) extends StateMachine[Data] { me =>

  val reader = rs match {
    case None => makeReader(keyPair)
    case Some(remoteNodeStaticPubKey) =>
      val writer = makeWriter(keyPair, remoteNodeStaticPubKey)
      val (writer1, _, msg) = writer write BinaryData.empty
      transport.send(prefix +: msg)
      writer1
  }

  become(HandshakeData(reader, BinaryData.empty), HANDSHAKE)
  def doProcess(change: Any): Unit = (data, change, state) match {
    case (HandshakeData(reader1, buffer), bd: BinaryData, HANDSHAKE) =>
      me stayWith HandshakeData(reader1, buffer ++ bd)
      process(Ping)

    case (HandshakeData(reader1, buffer), Ping, HANDSHAKE)
      if buffer.length >= expectedLength(reader1) =>

      require(buffer.head == prefix, "Invalid prefix in handshake buffer")
      val (payload, remainder) = buffer.tail.splitAt(expectedLength(reader1) - 1)

      reader1 read payload match {
        case (_, (decoder, encoder, ck), _) =>
          val encoder1 = ExtendedCipherState(encoder, ck)
          val decoder1 = ExtendedCipherState(decoder, ck)
          val d1 = CyphertextData(encoder1, decoder1, None, remainder)
          become(d1, WAITING_CYPHERTEXT)
          process(Ping)

        case (writer, _, _) =>
          writer write BinaryData.empty match {
            case (_, (encoder, decoder, ck), message) =>
              val encoder1 = ExtendedCipherState(encoder, ck)
              val decoder1 = ExtendedCipherState(decoder, ck)
              val d1 = CyphertextData(encoder1, decoder1, None, remainder)
              transport.send(prefix +: message)
              become(d1, WAITING_CYPHERTEXT)
              process(Ping)

            case (reader2, _, message) =>
              require(remainder.isEmpty, s"Unexpected data")
              val d1 = HandshakeData(reader2, remainder)
              transport.send(prefix +: message)
              become(d1, HANDSHAKE)
              process(Ping)
          }
      }

    // WAITING_CYPHERTEXT length phase

    case (cd: CyphertextData, (Send, data: BinaryData), WAITING_CYPHERTEXT) =>
      val (encoder1: CipherState, ciphertext: BinaryData) = encryptMsg(cd.enc, data)

      transport send ciphertext
      me stayWith cd.copy(enc = encoder1)

    case (cd: CyphertextData, bd: BinaryData, WAITING_CYPHERTEXT) =>
      me stayWith cd.copy(buffer = cd.buffer ++ bd)
      process(Ping)

    case (CyphertextData(encoder, decoder, None, buffer),
      Ping, WAITING_CYPHERTEXT) if buffer.length >= 18 =>

      val (ciphertext, remainder) = buffer splitAt 18
      val (decoder1, plaintext) = decoder.decryptWithAd(BinaryData.empty, ciphertext)
      val length = Some apply Protocol.uint16(plaintext, ByteOrder.BIG_ENDIAN)
      me stayWith CyphertextData(encoder, decoder1, length, remainder)
      process(Ping)

    case (CyphertextData(encoder, decoder, Some(length), buffer),
      Ping, WAITING_CYPHERTEXT) if buffer.length >= length =>

      val (ciphertext, remainder) = buffer.splitAt(length + 16)
      val (decoder1, plaintext) = decoder.decryptWithAd(BinaryData.empty, ciphertext)
      me stayWith CyphertextData(encoder, decoder1, length = None, remainder)
      me consume plaintext
      process(Ping)

    case _ =>
      // Let know if received an unhandled message
      Tools.log(s"TransportHandler: unhandled $data : $change")
  }
}

object TransportHandler {
  val prologue = "lightning" getBytes "UTF-8"
  val WAITING_CYPHERTEXT = "WaitingCyphertext"
  val HANDSHAKE = "Handshake"
  val prefix = 0.toByte
  val Ping = "Ping"
  val Send = "Send"

  def expectedLength(reader: HandshakeStateReader): Int =
    reader.messages.length match { case 3 | 2 => 50 case _ => 66 }

  def encryptMsg(enc: CipherState, plaintext: BinaryData): (CipherState, BinaryData) = {
    val plaintextAsUinteger16 = Protocol.writeUInt16(plaintext.length, ByteOrder.BIG_ENDIAN)
    val (enc1, ciphertext1) = enc.encryptWithAd(BinaryData.empty, plaintextAsUinteger16)
    val (enc2, ciphertext2) = enc1.encryptWithAd(BinaryData.empty, plaintext)
    (enc2, ciphertext1 ++ ciphertext2)
  }

  def makeWriter(localStatic: KeyPair, remoteStatic: BinaryData) =
    HandshakeState.initializeWriter(handshakePatternXK, prologue, localStatic,
      KeyPair(BinaryData.empty, BinaryData.empty), remoteStatic, BinaryData.empty,
      Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions, random)

  def makeReader(localStatic: KeyPair) =
    HandshakeState.initializeReader(handshakePatternXK, prologue, localStatic,
      KeyPair(BinaryData.empty, BinaryData.empty), BinaryData.empty, BinaryData.empty,
      Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions, random)

  sealed trait Data
  case class HandshakeData(reader: HandshakeStateReader, buffer: BinaryData) extends Data
  case class CyphertextData(enc: CipherState, dec: CipherState, length: Option[Int],
                            buffer: BinaryData) extends Data
}

// A key is to be rotated after a party sends of decrypts 1000 messages with it
case class ExtendedCipherState(cs: CipherState, ck: BinaryData) extends CipherState { me =>

  def encryptWithAd(ad: BinaryData, plaintext: BinaryData) =
    cs match {
      case InitializedCipherState(k, 999, _) =>
        val (_, ciphertext) = cs.encryptWithAd(ad, plaintext)
        val (chainKey1, material1) = SHA256HashFunctions.hkdf(ck, k)
        copy(cs = cs initializeKey material1, ck = chainKey1) -> ciphertext

      case _: InitializedCipherState =>
        val (cs1, ciphertext) = cs.encryptWithAd(ad, plaintext)
        copy(cs = cs1) -> ciphertext

      case _: UnitializedCipherState =>
        me -> plaintext
    }

  def decryptWithAd(ad: BinaryData, ciphertext: BinaryData) =
    cs match {
      case InitializedCipherState(k, 999, _) =>
        val (_, plaintext) = cs.decryptWithAd(ad, ciphertext)
        val (chainKey1, material1) = SHA256HashFunctions.hkdf(ck, k)
        copy(cs = cs initializeKey material1, ck = chainKey1) -> plaintext

      case _: InitializedCipherState =>
        val (cs1, plaintext) = cs.decryptWithAd(ad, ciphertext)
        copy(cs = cs1) -> plaintext

      case _: UnitializedCipherState =>
        me -> ciphertext
    }

  def cipher = cs.cipher
  val hasKey = cs.hasKey
}