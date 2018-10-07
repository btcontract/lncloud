package com.lightning.walletapp.helper

import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.lightning.walletapp.ln.Tools.{Bytes, bin2readable, random}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.aesZygoteCodec
import com.lightning.walletapp.ln.wire.AESZygote
import org.bitcoinj.core.Utils.HEX
import scodec.bits.BitVector
import javax.crypto.Cipher
import scala.util.Try


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int) =
    Cipher getInstance "AES/CBC/PKCS5Padding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  private[this] val ivLength = 16
  def dec(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data
  def enc(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data

  // Used for Object -> Json -> Encrypted -> Zygote -> Hex

  def encReadable2Hex(plain: String, key: Bytes) = {
    val zygote = encBytes(plain getBytes "UTF-8", key)
    aesZygoteCodec.encode(zygote).require.toHex
  }

  def decHex2Readable(raw: String, key: Bytes) =
    decBytes(HEX decode raw, key) map bin2readable

  // Used for Object -> Scodec -> Encrypted -> Zygote

  def encBytes(plain: Bytes, key: Bytes) = {
    val initialVector = random getBytes ivLength
    val cipher = enc(plain, key, initialVector)
    AESZygote(v = 1, initialVector, cipher)
  }

  def decBytes(raw: Bytes, key: Bytes) = {
    val aesz = aesZygoteCodec decode BitVector(raw)
    decZygote(aesz.require.value, key)
  }

  def decZygote(aesz: AESZygote, key: Bytes) =
    Try apply dec(aesz.ciphertext, key, aesz.iv)
}