package com.lightning.wallet.helper

import com.lightning.wallet.ln.Tools.{Bytes, random}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.lightning.wallet.ln.crypto.MultiStreamUtils.aconcat
import fr.acinq.bitcoin.BinaryData
import javax.crypto.Cipher


object AES {
  def cipher(key: Bytes, initVector: Bytes, mode: Int) =
    Cipher getInstance "AES/CTR/NoPadding" match { case aesCipher =>
      val ivParameterSpec: IvParameterSpec = new IvParameterSpec(initVector)
      aesCipher.init(mode, new SecretKeySpec(key, "AES"), ivParameterSpec)
      aesCipher
    }

  def encCypher(key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.ENCRYPT_MODE)
  def decCypher(key: Bytes, initVector: Bytes) = cipher(key, initVector, Cipher.DECRYPT_MODE)
  def enc(data: Bytes, key: Bytes, initVector: Bytes) = encCypher(key, initVector) doFinal data
  def dec(data: Bytes, key: Bytes, initVector: Bytes) = decCypher(key, initVector) doFinal data
  def unpack(raw: Bytes): (Bytes, Bytes) = (raw.slice(1, 1 + ivLength), raw drop 1 + ivLength)
  def pack(iv: Bytes, cipher: Bytes): Bytes = aconcat(Array(version), iv, cipher)
  private[this] val version = 1.toByte
  private[this] val ivLength = 16

  def encode(plaintext: String, key: Bytes): BinaryData = {
    // Takes any string and returns an encoded string with iv
    // Can be directly sent over internet connection

    val initVec = random getBytes ivLength
    val plainbytes = plaintext getBytes "UTF-8"
    val cipherbytes = enc(plainbytes, key, initVec)
    pack(initVec, cipherbytes)
  }

  def decode(packed: Bytes, key: Bytes): BinaryData = {
    // Takes packed format with has version number and iv
    val (initVec, cipherbytes) = unpack(packed)
    dec(cipherbytes, key, initVec)
  }
}