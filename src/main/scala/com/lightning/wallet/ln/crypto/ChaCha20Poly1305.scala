package com.lightning.wallet.ln.crypto

import com.lightning.wallet.ln.Exceptions._
import com.lightning.wallet.ln.crypto.MultiStreamUtils.aconcat
import org.spongycastle.crypto.params.{KeyParameter, ParametersWithIV}
import org.spongycastle.crypto.engines.{ChaCha7539Engine, ChaChaEngine}
import org.spongycastle.crypto.SkippingStreamCipher
import fr.acinq.bitcoin.Protocol.writeUInt64
import com.lightning.wallet.ln.Tools.Bytes
import java.nio.ByteOrder.LITTLE_ENDIAN


object ChaCha20Poly1305 {
  def encrypt(key: Bytes, nonce: Bytes, plaintext: Bytes, aad: Bytes): (Bytes, Bytes) = {
    val polykey = ChaCha20.process(new Bytes(32), key, nonce, encrypt = true, skipBlock = false)
    val ciphertext = ChaCha20.process(plaintext, key, nonce, encrypt = true, skipBlock = true)
    ciphertext -> Poly1305.mac(data = pack(aad, ciphertext), key = polykey)
  }

  def decrypt(key: Bytes, nonce: Bytes, ciphertext: Bytes, aad: Bytes, mac: Bytes): Bytes = {
    val polykey = ChaCha20.process(new Bytes(32), key, nonce, encrypt = true, skipBlock = false)
    val same = Poly1305.mac(data = pack(aad, ciphertext), key = polykey) sameElements mac

    if (!same) throw ChannelException(CHACHA_INVALID_MAC)
    else ChaCha20.process(ciphertext, key, nonce,
      encrypt = false, skipBlock = true)
  }

  def pack(aad: Bytes, txt: Bytes) =
    aconcat(aad, pad16(aad), txt, pad16(txt),
      writeUInt64(aad.length, LITTLE_ENDIAN),
      writeUInt64(txt.length, LITTLE_ENDIAN),
      Array.emptyByteArray)

  def pad16(data: Bytes): Bytes =
    if (data.length % 16 == 0) Array.empty
    else Array.fill(16 - data.length % 16)(0)
}

trait SkippingStreamCipherEngine {
  def getEngine: SkippingStreamCipher
  def process(data: Bytes, key: Bytes, nonce: Bytes,
              encrypt: Boolean, skipBlock: Boolean) = {

    val engine = getEngine
    val parameter = new KeyParameter(key)
    val params = new ParametersWithIV(parameter, nonce)
    val resultData = new Bytes(data.length)
    engine.init(encrypt, params)

    // Skip 1 block == set skip to true instead of false
    if (skipBlock) engine.processBytes(new Bytes(64), 0, 64, new Bytes(64), 0)
    val same = engine.processBytes(data, 0, data.length, resultData, 0) == data.length
    if (!same) throw ChannelException(CHACHA_INVALID_DATA_LENGTH)
    resultData
  }
}

object ChaCha20 extends SkippingStreamCipherEngine { def getEngine = new ChaCha7539Engine }
object ChaCha20Legacy extends SkippingStreamCipherEngine { def getEngine = new ChaChaEngine }

object Poly1305 {
  def mac(key: Bytes, data: Bytes) = {
    val poly = new org.spongycastle.crypto.macs.Poly1305
    val out = new Bytes(16)

    poly init new KeyParameter(key)
    poly.update(data, 0, data.length)
    poly.doFinal(out, 0)
    out
  }
}