package com.lightning.walletapp.helper

import com.lightning.walletapp.ln.Tools.{Bytes, random}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.lightning.walletapp.ln.wire.LightningMessageCodecs.aesZygoteCodec
import com.lightning.walletapp.ln.wire.AESZygote
import scodec.bits.BitVector
import javax.crypto.Cipher


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int) =
    Cipher getInstance "AES/CBC/PKCS7Padding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  def enc(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.ENCRYPT_MODE) doFinal data
  def dec(data: Bytes, key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.DECRYPT_MODE) doFinal data
  private[this] val ivLength = 16

  def encode(plain: String, key: Bytes) = {
    // Takes any input string and returns a zygote

    val initVec = random getBytes ivLength
    val cipher = enc(plain getBytes "UTF-8", key, initVec)
    val zygote = AESZygote(v = 1, initVec, cipher)
    aesZygoteCodec.encode(zygote).require.toHex
  }

  def decode(raw: String, key: Bytes) = {
    val bitVector = BitVector.fromHex(raw).get
    val zygote = aesZygoteCodec.decode(bitVector).require.value
    new String(dec(zygote.ciphertext, key, zygote.iv), "UTF-8")
  }
}