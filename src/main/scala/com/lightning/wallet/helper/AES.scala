package com.lightning.wallet.helper

import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.lightning.wallet.ln.Tools.Bytes
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
}