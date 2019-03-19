package com.lightning.walletapp.ln

import com.lightning.walletapp.ln.Tools.runAnd
import fr.acinq.bitcoin.Crypto.PrivateKey
import language.implicitConversions
import crypto.RandomGenerator
import scodec.bits.ByteVector
import java.util


object \ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* Got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  val random = new RandomGenerator

  def runAnd[T](result: T)(action: Any): T = result
  def bin2readable(bin: Bytes) = new String(bin, "UTF-8")
  def errlog(error: Throwable): Unit = error.printStackTrace
  def log(consoleMessage: String): Unit = System.out.println("LN", consoleMessage)
  def randomPrivKey = PrivateKey(ByteVector.view(random getBytes 32), compressed = true)
  def wrap(run: => Unit)(go: => Unit) = try go catch none finally run
  def none: PartialFunction[Any, Unit] = { case _ => }

  def toMap[T, K, V](source: Seq[T], keyFun: T => K, valFun: T => V): Map[K, V] = {
    val premap = for (mapElement <- source) yield keyFun(mapElement) -> valFun(mapElement)
    premap.toMap
  }

  def fromShortId(id: Long) = {
    val blockHeight = id.>>(40).&(0xFFFFFF).toInt
    val txIndex = id.>>(16).&(0xFFFFFF).toInt
    val outputIndex = id.&(0xFFFF).toInt
    (blockHeight, txIndex, outputIndex)
  }

  def toShortIdOpt(blockHeight: Long, txIndex: Long, outputIndex: Long): Option[Long] = {
    val result = blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)
    if (txIndex < 0) None else Some(result)
  }

  def toLongId(txid: ByteVector, fundingOutputIndex: Int) = {
    require(fundingOutputIndex < 65536, "Index is larger than 65535")
    val part2 = txid(30).^(fundingOutputIndex >> 8).toByte
    val part3 = txid(31).^(fundingOutputIndex).toByte
    txid.take(30) :+ part2 :+ part3
  }
}

object Features {
  val OPTION_DATA_LOSS_PROTECT_MANDATORY = 0
  val OPTION_DATA_LOSS_PROTECT_OPTIONAL = 1

  implicit def binData2BitSet(featuresBinaryData: ByteVector): util.BitSet = util.BitSet.valueOf(featuresBinaryData.reverse.toArray)
  def dataLossProtect(bitset: util.BitSet) = bitset.get(OPTION_DATA_LOSS_PROTECT_OPTIONAL) || bitset.get(OPTION_DATA_LOSS_PROTECT_MANDATORY)
  def isBitSet(position: Int, bitField: Byte): Boolean = bitField.&(1 << position) == (1 << position)

  def areSupported(bitset: util.BitSet): Boolean = {
    val mandatoryFeatures: Set[Int] = Set(OPTION_DATA_LOSS_PROTECT_MANDATORY)
    def mandatoryUnsupported(n: Int) = bitset.get(n) && !mandatoryFeatures.contains(n)
    !(0 until bitset.length by 2 exists mandatoryUnsupported)
  }
}

class LightningException(reason: String = "Failure") extends RuntimeException(reason)
case class HTLCHasExpired(norm: NormalData, htlc: Htlc) extends LightningException

// STATE MACHINE

abstract class StateMachine[T] {
  def become(freshData: T, freshState: String) =
    runAnd { data = freshData } { state = freshState }

  def doProcess(change: Any)
  var state: String = _
  var data: T = _
}