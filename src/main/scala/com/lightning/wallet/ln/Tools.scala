package com.lightning.wallet.ln

import com.lightning.wallet.ln.Tools.wrap
import language.implicitConversions
import fr.acinq.bitcoin.BinaryData
import crypto.RandomGenerator
import java.util


object \ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  val random = new RandomGenerator
  def errlog(err: Throwable): Unit = err.printStackTrace
  def log(message: String): Unit = println("LN", message)
  def runAnd[T](resultData: T)(action: Any): T = resultData
  def wrap(run: => Unit)(go: => Unit) = try go catch none finally run
  def none: PartialFunction[Any, Unit] = { case _ => }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }

  def toLongId(fundingHash: BinaryData, fundingOutputIndex: Int): BinaryData =
    if (fundingOutputIndex >= 65536 | fundingHash.size != 32) throw new LightningException
    else fundingHash.take(30) :+ fundingHash.data(30).^(fundingOutputIndex >> 8).toByte :+
      fundingHash.data(31).^(fundingOutputIndex).toByte
}

object Features {
  val OPTION_DATA_LOSS_PROTECT_MANDATORY = 0
  val OPTION_DATA_LOSS_PROTECT_OPTIONAL = 1

  implicit def binData2BitSet(data: BinaryData): util.BitSet = util.BitSet valueOf data.reverse.toArray
  def areSupported(bitset: util.BitSet) = !(0 until bitset.length by 2 exists bitset.get)

  def dataLossProtect(bitset: util.BitSet) =
    bitset.get(OPTION_DATA_LOSS_PROTECT_OPTIONAL) ||
      bitset.get(OPTION_DATA_LOSS_PROTECT_MANDATORY)
}

class LightningException extends RuntimeException

// STATE MACHINE

abstract class StateMachine[T] {
  def become(freshData: T, freshState: String) =
    wrap { data = freshData } { state = freshState }

  def doProcess(change: Any)
  var state: String = _
  var data: T = _
}