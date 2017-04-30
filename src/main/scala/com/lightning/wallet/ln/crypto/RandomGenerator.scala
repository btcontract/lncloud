package com.lightning.wallet.ln.crypto

import java.io.{ByteArrayOutputStream, InputStream}
import com.lightning.wallet.ln.Tools.Bytes
import java.security.SecureRandom


trait ByteStream {
  def getBytes(size: Int): Bytes
}

class RandomGenerator
extends SecureRandom
with ByteStream
{
  def getBytes(size: Int) = {
    val array = new Bytes(size)
    super.nextBytes(array)
    array
  }
}

object MultiStreamUtils {
  def aread(stream: InputStream, nums: Int*) =
    for (num <- nums) yield {
      val buffer = new Bytes(num)
      stream.read(buffer)
      buffer
    }

  def awrite(stream: ByteArrayOutputStream, arrs: Bytes*) = {
    for (arrayDataToWrite <- arrs) stream write arrayDataToWrite
    stream
  }

  def aconcat(arrays: Bytes*): Bytes = {
    val output = new ByteArrayOutputStream(8)
    for (array <- arrays) output write array
    output.toByteArray
  }
}
