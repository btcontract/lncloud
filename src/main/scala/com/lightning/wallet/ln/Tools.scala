package com.lightning.wallet.ln

import com.lightning.wallet.ln.Tools._
import language.implicitConversions
import fr.acinq.bitcoin.BinaryData
import crypto.RandomGenerator


object SetEx {
  // Matching sets as algebraic data structures
  def unapplySeq[T](s: Set[T] /* got a set */) = Some(s.toSeq)
}

object \ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  val random = new RandomGenerator
  def log(message: String): Unit = println("LN", message)
  def wrap(run: => Unit)(go: => Unit) = try go catch none finally run
  def none: PartialFunction[Any, Unit] = { case _ => }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }
}

object Features {
  val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 2
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3

  implicit def binData2BitSet(data: BinaryData): java.util.BitSet = java.util.BitSet valueOf data.reverse.toArray
  def initialRoutingSync(bitset: java.util.BitSet): Boolean = bitset get INITIAL_ROUTING_SYNC_BIT_OPTIONAL
  def areSupported(bitset: java.util.BitSet): Boolean = !(0 until bitset.length by 2 exists bitset.get)
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