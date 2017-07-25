package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Tools._
import java.text.{DecimalFormat, DecimalFormatSymbols}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import com.lightning.wallet.ln.crypto.RandomGenerator
import language.implicitConversions
import org.bitcoinj.core.Coin
import java.util.Locale


object ~ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  type LightningMessages = Vector[LightningMessage]
  val random = new RandomGenerator

  def runAnd[T](result: T)(action: Any): T = result
  def log(message: String): Unit = println(s"LNCloud: $message")
  def wrap(run: => Unit)(go: => Unit) = try go catch none finally run
  def none: PartialFunction[Any, Unit] = { case _ => }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)

  def toLongId(fundingHash: BinaryData, fundingOutputIndex: Int): BinaryData = {
    if (fundingOutputIndex >= 65536 | fundingHash.size != 32) throw new LightningException
    fundingHash.take(30) :+ fundingHash.data(30).^(fundingOutputIndex >> 8).toByte :+
      fundingHash.data(31).^(fundingOutputIndex).toByte
  }
}

object MSat {
  val satFactor = 1000L
  val btcFactor = 100000000000L
  val locale = new Locale("en", "US")
  val baseFiat = new DecimalFormat("#,###,###.##")
  val baseSat = new DecimalFormat("###,###,###.###")
  val symbols = new DecimalFormatSymbols(locale)
  baseFiat setDecimalFormatSymbols symbols
  baseSat setDecimalFormatSymbols symbols

  def withSign(sum: String): String = s"ⓢ\u00A0$sum"
  def btcBigDecimal2MilliSatoshi(btc: BigDecimal): MilliSatoshi = MilliSatoshi(amount = (btc * btcFactor).toLong)
  def satString2MilliSatoshi(sat: String): MilliSatoshi = MilliSatoshi(amount = (BigDecimal(sat) * satFactor).toLong)
  implicit def milliSatoshi2String(msat: MilliSatoshi): String = baseSat format BigDecimal(msat.amount) / satFactor
  implicit def satoshi2String(msat: Satoshi): String = baseSat format msat.amount
  implicit def coin2String(coin: Coin): String = baseSat format coin.value

  implicit def milliSatoshi2Coin(msat: MilliSatoshi): Coin = Coin.valueOf(msat.amount / satFactor)
  implicit def coin2MilliSatoshi(coin: Coin): MilliSatoshi = MilliSatoshi(coin.value * satFactor)
}

object Features {
  val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 2
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3

  implicit def binData2BitSet(data: BinaryData): java.util.BitSet = java.util.BitSet valueOf data.reverse.toArray
  def initialRoutingSync(bitset: java.util.BitSet): Boolean = bitset get INITIAL_ROUTING_SYNC_BIT_OPTIONAL
  def areSupported(bitset: java.util.BitSet): Boolean = !(0 until bitset.length by 2 exists bitset.get)
}

class LightningException extends RuntimeException
case class ExtendedException[T](details: T) extends LightningException

// STATE MACHINE

abstract class StateMachine[T] { grounding =>
  def process(change: Any) = grounding synchronized doProcess(change = change)
  def become(data1: T, state1: String) = wrap { data = data1 } { state = state1 }
  def stayWith(data1: T) = become(data1, state)
  def doProcess(change: Any)
  var state: String = _
  var data: T = _
}