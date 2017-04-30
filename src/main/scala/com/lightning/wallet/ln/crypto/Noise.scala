package com.lightning.wallet.ln.crypto

import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.bitcoin.Protocol.writeUInt64
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.math.BigInteger


object Noise {
  sealed trait MessagePattern
  case object S extends MessagePattern
  case object E extends MessagePattern
  case object EE extends MessagePattern
  case object ES extends MessagePattern
  case object SE extends MessagePattern
  case object SS extends MessagePattern
  type MessagePatterns = List[MessagePattern]
  type MessagePatternsList = List[MessagePatterns]

  case class KeyPair(pub: BinaryData, priv: BinaryData)
  case class HandshakePattern(name: String, initiatorPreMessages: MessagePatterns,
                              responderPreMessages: MessagePatterns, messages: MessagePatternsList)

  val handshakePatternNN = HandshakePattern("NN", Nil, Nil, List(E) :: List(E, EE) :: Nil)
  val handshakePatternXK = HandshakePattern("XK", Nil, S :: Nil, List(E, ES) :: List(E, EE) :: List(S, SE) :: Nil)


  trait DHFunctions {
    def dh(keyPair: KeyPair, publicKey: BinaryData): BinaryData
    def generateKeyPair(priv: BinaryData): KeyPair

    val dhLen: Int
    val pubKeyLen: Int
    val name: String
  }

  object Secp256k1DHFunctions extends DHFunctions {
    def dh(keyPair: KeyPair, pubKey: BinaryData) = Crypto sha256 {
      val pointGBigInteger = new BigInteger(1, keyPair.priv.take(32).toArray)
      val ecPoint = Crypto.curve.getCurve.decodePoint(pubKey).multiply(pointGBigInteger)
      ecPoint.normalize getEncoded true
    }

    def generateKeyPair(priv: BinaryData) = {
      require(priv.length == 32, "DH key must be of length 32")
      KeyPair(Crypto.publicKeyFromPrivateKey(priv :+ 1.toByte), priv)
    }

    val name = "secp256k1"
    val pubKeyLen = 33
    val dhLen = 32
  }

  trait CipherFunctions {
    def encrypt(k: BinaryData, n: Long, ad: BinaryData, plaintext: BinaryData): BinaryData
    def decrypt(k: BinaryData, n: Long, ad: BinaryData, ciphertext: BinaryData): BinaryData
    val name: String
  }

  object Chacha20Poly1305CipherFunctions extends CipherFunctions {
    def encrypt(k: BinaryData, n: Long, ad: BinaryData, plaintext: BinaryData) = {
      val (ciphertext, mac) = ChaCha20Poly1305.encrypt(key = k, nonce(n), plaintext, ad)
      ciphertext ++ mac
    }

    def decrypt(k: BinaryData, n: Long, ad: BinaryData, ciphertextAndMac: BinaryData) = {
      val (ciphertext, mac) = (ciphertextAndMac dropRight 16, ciphertextAndMac takeRight 16)
      ChaCha20Poly1305.decrypt(k, nonce(n), ciphertext.toArray, ad, mac.toArray)
    }

    def nonce(n: Long): BinaryData =
      baseBin ++ writeUInt64(n, LITTLE_ENDIAN)

    val name = "ChaChaPoly"
    val baseBin = BinaryData("00000000")
  }

  trait HashFunctions {
    def hmacHash(key: BinaryData, data: BinaryData): BinaryData
    def hash(source: BinaryData): BinaryData

    val blockLen: Int
    val hashLen: Int
    val name: String

    def hkdf(chainingKey: BinaryData, inputMaterial: BinaryData) = {
      val tempkey = hmacHash(key = chainingKey, data = inputMaterial)
      val output1 = hmacHash(key = tempkey, 0x01.toByte :: Nil)
      val output2 = hmacHash(tempkey, output1 :+ 0x02.toByte)
      output1 -> output2
    }
  }

  object SHA256HashFunctions extends HashFunctions {
    def hmacHash(key: BinaryData, data: BinaryData) = Digests.hmacSha256(key, data)
    def hash(source: BinaryData) = Crypto.sha256(source)

    val name = "SHA256"
    val blockLen = 64
    val hashLen = 32
  }

  trait CipherState {
    def cipher: CipherFunctions
    def initializeKey(key: BinaryData): CipherState = CipherState(key, cipher)
    def encryptWithAd(ad: BinaryData, plaintext: BinaryData): (CipherState, BinaryData)
    def decryptWithAd(ad: BinaryData, ciphertext: BinaryData): (CipherState, BinaryData)
    val hasKey: Boolean
  }

  case class UnitializedCipherState(cipher: CipherFunctions) extends CipherState { me =>
    def decryptWithAd(ad: BinaryData, ciphertext: BinaryData) = me -> ciphertext
    def encryptWithAd(ad: BinaryData, plaintext: BinaryData) = me -> plaintext
    val hasKey = false
  }

  case class InitializedCipherState(k: BinaryData, n: Long, cipher: CipherFunctions) extends CipherState { me =>
    def decryptWithAd(ad: BinaryData, ciphertext: BinaryData) = copy(n = n + 1) -> cipher.decrypt(k, n, ad, ciphertext)
    def encryptWithAd(ad: BinaryData, plaintext: BinaryData) = copy(n = n + 1) -> cipher.encrypt(k, n, ad, plaintext)
    require(k.length == 32)
    val hasKey = true
  }

  object CipherState {
    def apply(k: BinaryData, cipher: CipherFunctions) =
      if (k.length == 32) InitializedCipherState(k, 0, cipher)
      else if (k.length == 0) UnitializedCipherState(cipher)
      else throw new Exception("Invalid k length")

    def apply(cipher: CipherFunctions) =
      UnitializedCipherState(cipher)
  }

  case class SymmetricState(cipherState: CipherState, ck: BinaryData,
                            h: BinaryData, hashFunctions: HashFunctions) {

    def mixKey(inputKeyMaterial: BinaryData): SymmetricState = {
      val (ck1, tempk) = hashFunctions.hkdf(chainingKey = ck, inputMaterial = inputKeyMaterial)
      val tempk1: BinaryData = hashFunctions.hashLen match { case 32 => tempk case 64 => tempk take 32 }
      copy(cipherState = cipherState initializeKey tempk1, ck = ck1)
    }

    def mixHash(data: BinaryData): SymmetricState =
      copy(h = hashFunctions hash h ++ data)

    def encryptAndHash(plaintext: BinaryData) = {
      val (cipherstate1, ciphertext) = cipherState.encryptWithAd(h, plaintext)
      copy(cipherState = cipherstate1).mixHash(ciphertext) -> ciphertext
    }

    def decryptAndHash(ciphertext: BinaryData) = {
      val (cipherstate1, plaintext) = cipherState.decryptWithAd(h, ciphertext)
      copy(cipherState = cipherstate1).mixHash(ciphertext) -> plaintext
    }

    def split = {
      val (tempk1, tempk2) = hashFunctions.hkdf(ck, BinaryData.empty)
      val part1 = cipherState.initializeKey(tempk1 take 32)
      val part2 = cipherState.initializeKey(tempk2 take 32)
      (part1, part2, ck)
    }
  }

  object SymmetricState {
    def mkHash(protocolName: BinaryData, hashFunctions: HashFunctions): BinaryData =
      if (protocolName.length > hashFunctions.hashLen) hashFunctions.hash(protocolName)
      else protocolName ++ Seq.fill[Byte](hashFunctions.hashLen - protocolName.length)(0)

    def mk(hash: BinaryData, cipherFunctions: CipherFunctions, hashFunctions: HashFunctions) =
      new SymmetricState(CipherState(cipherFunctions), ck = hash, h = hash, hashFunctions)
  }

  sealed trait HandshakeState
  case class HandshakeStateWriter(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair,
                                  e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions,
                                  byteStream: ByteStream) extends HandshakeState { me =>

    def toReader = HandshakeStateReader(messages, state, s, e, rs, re, dh, byteStream)

    def fold(pts: MessagePatterns) =
      pts.foldLeft(me -> BinaryData.empty) {

        case Tuple2(Tuple2(writer, buffer), E) =>
          val e1 = dh.generateKeyPair(byteStream getBytes dh.dhLen)
          (writer.copy(state = writer.state mixHash e1.pub, e = e1), buffer ++ e1.pub)

        case Tuple2(Tuple2(writer, buffer), S) =>
          val (state1, ciphertext) = writer.state encryptAndHash s.pub
          Tuple2(writer.copy(state = state1), buffer ++ ciphertext)

        case Tuple2(Tuple2(writer, buffer), EE) =>
          val state1 = writer.state mixKey dh.dh(writer.e, writer.re)
          Tuple2(writer.copy(state = state1), buffer)

        case Tuple2(Tuple2(writer, buffer), SS) =>
          val state1 = writer.state mixKey dh.dh(writer.s, writer.rs)
          Tuple2(writer.copy(state = state1), buffer)

        case Tuple2(Tuple2(writer, buffer), ES) =>
          val state1 = writer.state mixKey dh.dh(writer.e, writer.rs)
          Tuple2(writer.copy(state = state1), buffer)

        case Tuple2(Tuple2(writer, buffer), SE) =>
          val state1 = writer.state mixKey dh.dh(writer.s, writer.re)
          Tuple2(writer.copy(state = state1), buffer)
      }

    def write(payload: BinaryData) = {
      val (writer1, buffer1) = fold(messages.head)
      val (state1, ciphertext) = writer1.state encryptAndHash payload
      val writer2 = writer1.copy(messages = messages.tail, state = state1)
      val parts = if (messages.tail.isEmpty) writer2.state.split else null
      (writer2.toReader, parts, buffer1 ++ ciphertext)
    }
  }

  case class HandshakeStateReader(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair,
                                  e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions,
                                  byteStream: ByteStream) extends HandshakeState { me =>

    def toWriter = HandshakeStateWriter(messages, state, s, e, rs, re, dh, byteStream)

    def fold(pts: MessagePatterns, message: BinaryData) =
      messages.head.foldLeft(me -> message) {

        case Tuple2(Tuple2(reader, buffer), E) =>
          val (re1, buffer1) = buffer.splitAt(dh.pubKeyLen)
          (reader.copy(state = reader.state mixHash re1, re = re1), buffer1)

        case Tuple2(Tuple2(reader, buffer), S) =>
          val hasKey = reader.state.cipherState.hasKey
          val len = if (hasKey) dh.pubKeyLen + 16 else dh.pubKeyLen

          val (temp, buffer1) = buffer.splitAt(len)
          val (state1, rs1) = reader.state decryptAndHash temp
          Tuple2(reader.copy(state = state1, rs = rs1), buffer1)

        case Tuple2(Tuple2(reader, buffer), EE) =>
          val state1 = reader.state mixKey dh.dh(reader.e, reader.re)
          Tuple2(reader.copy(state = state1), buffer)

        case Tuple2(Tuple2(reader, buffer), SS) =>
          val state1 = reader.state mixKey dh.dh(reader.s, reader.rs)
          Tuple2(reader.copy(state = state1), buffer)

        case Tuple2(Tuple2(reader, buffer), ES) =>
          val state1 = reader.state mixKey dh.dh(reader.s, reader.re)
          Tuple2(reader.copy(state = state1), buffer)

        case Tuple2(Tuple2(reader, buffer), SE) =>
          val state1 = reader.state mixKey dh.dh(reader.e, reader.rs)
          Tuple2(reader.copy(state = state1), buffer)
      }

    def read(message: BinaryData) = {
      val (reader1, buffer1) = fold(messages.head, message)
      val (state1, payload) = reader1.state decryptAndHash buffer1
      val reader2 = reader1.copy(messages = messages.tail, state = state1)
      val parts = if (messages.tail.isEmpty) reader2.state.split else null
      (reader2.toWriter, parts, payload)
    }
  }

  object HandshakeState {
    private def makeSymmetricState(handshakePattern: HandshakePattern, prologue: BinaryData,
                                   dh: DHFunctions, cipher: CipherFunctions, hash: HashFunctions) = {

      val name = "Noise_" + handshakePattern.name + "_" + dh.name + "_" + cipher.name + "_" + hash.name
      val symmetricState = SymmetricState.mk(SymmetricState.mkHash(name getBytes "UTF-8", hash), cipher, hash)
      symmetricState mixHash prologue
    }

    def initializeWriter(handshakePattern: HandshakePattern, prologue: BinaryData, s: KeyPair, e: KeyPair,
                         rs: BinaryData, re: BinaryData, dh: DHFunctions, cipher: CipherFunctions,
                         hash: HashFunctions, byteStream: ByteStream): HandshakeStateWriter = {

      val symmetricState = makeSymmetricState(handshakePattern, prologue, dh, cipher, hash)
      val symmetricState1 = handshakePattern.initiatorPreMessages.foldLeft(symmetricState) {
        case (state, EE | ES | SE | SS) => throw new Exception("Invalid state initializeWriter")
        case (state, E) => state mixHash e.pub
        case (state, S) => state mixHash s.pub
      }

      val symmetricState2 = handshakePattern.responderPreMessages.foldLeft(symmetricState1) {
        case (state, EE | ES | SE | SS) => throw new Exception("Invalid state initializeWriter")
        case (state, E) => state mixHash re
        case (state, S) => state mixHash rs
      }

      HandshakeStateWriter(handshakePattern.messages,
        symmetricState2, s, e, rs, re, dh, byteStream)
    }

    def initializeReader(handshakePattern: HandshakePattern, prologue: BinaryData, s: KeyPair, e: KeyPair,
                         rs: BinaryData, re: BinaryData, dh: DHFunctions, cipher: CipherFunctions,
                         hash: HashFunctions, byteStream: ByteStream): HandshakeStateReader = {

      val symmetricState = makeSymmetricState(handshakePattern, prologue, dh, cipher, hash)
      val symmetricState1 = handshakePattern.initiatorPreMessages.foldLeft(symmetricState) {
        case (state, EE | ES | SE | SS) => throw new Exception("Invalid state initializeReader")
        case (state, E) => state mixHash re
        case (state, S) => state mixHash rs
      }

      val symmetricState2 = handshakePattern.responderPreMessages.foldLeft(symmetricState1) {
        case (state, EE | ES | SE | SS) => throw new Exception("Invalid state initializeReader")
        case (state, E) => state mixHash e.pub
        case (state, S) => state mixHash s.pub
      }

      HandshakeStateReader(handshakePattern.messages,
        symmetricState2, s, e, rs, re, dh, byteStream)
    }
  }
}