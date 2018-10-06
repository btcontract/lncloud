package com.lightning.walletapp.helper

import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.lightning.walletapp.ln.Tools.{Bytes, bin2readable, random}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.aesZygoteCodec
import com.lightning.walletapp.ln.wire.AESZygote
import org.bitcoinj.core.Utils.HEX
import fr.acinq.bitcoin.BinaryData
import scodec.bits.BitVector
import javax.crypto.Cipher
import scala.util.Try


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int) =
    Cipher getInstance "AES/CBC/PKCS7Padding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  private[this] val ivLength = 16
  def dec(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data
  def enc(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data
  def encHex(hex: String, keyHex: String) = encBytes(BinaryData(hex).toArray, BinaryData(keyHex).toArray)

  // Used for Object -> Json -> Zygote -> Hex

  def encReadable2Hex(plain: String, key: Bytes) = {
    val zygote = encBytes(plain getBytes "UTF-8", key)
    aesZygoteCodec.encode(zygote).require.toHex
  }

  def decHex2Readable(raw: String, key: Bytes) =
    decBytes(HEX decode raw, key) map bin2readable

  // Used for Object -> Scodec -> Zygote

  def encBytes(plain: Bytes, key: Bytes) = {
    val initialVector = random getBytes ivLength
    val cipher = enc(plain, key, initialVector)
    AESZygote(v = 1, initialVector, cipher)
  }

  def decBytes(raw: Bytes, key: Bytes) = Try {
    val zygoteRes = aesZygoteCodec decode BitVector(raw)
    val AESZygote(_, iv, cipher) = zygoteRes.require.value
    dec(cipher, key, iv)
  }
}