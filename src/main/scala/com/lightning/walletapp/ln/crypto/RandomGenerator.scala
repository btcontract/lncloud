package com.lightning.walletapp.ln.crypto

import java.io._
import java.security.SecureRandom
import com.google.common.io.ByteStreams
import com.lightning.walletapp.ln.Tools.Bytes


trait ByteStream { def getBytes(size: Int): Bytes }
class RandomGenerator extends SecureRandom with ByteStream
{
  def getBytes(size: Int) = {
    val array = new Bytes(size)
    super.nextBytes(array)
    array
  }
}

object MultiStreamUtils {
  def readone(stream: InputStream) =
    aread(stream, stream.available).head

  def aread(stream: InputStream, nums: Int*) =
    for (num <- nums) yield {
      val buffer = new Bytes(num)
      stream.read(buffer)
      buffer
    }

  def writeone(inputData: Bytes, out: OutputStream) = {
    val inputStream = new ByteArrayInputStream(inputData)
    ByteStreams.copy(inputStream, out)
    inputStream.close
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