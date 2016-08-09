package com.btcontract.lncloud.crypto

import com.btcontract.lncloud.Utils.Bytes
import java.security.SecureRandom


class RandomGenerator extends SecureRandom {
  // Supplement seed on startup to add randomness
  setSeed(System.currentTimeMillis)
  setSeed(nextInt)

  def getBytes(size: Int) = {
    val array = new Bytes(size)
    super.nextBytes(array)
    array
  }

  override def nextInt = {
    val tmpBuffer = getBytes(4)
    val x1 = (tmpBuffer(0) & 0xff) << 24
    val x2 = (tmpBuffer(1) & 0xff) << 16
    val x3 = (tmpBuffer(2) & 0xff) << 8
    val x4 = tmpBuffer(3) & 0xff
    x1 | x2 | x3 | x4
  }
}