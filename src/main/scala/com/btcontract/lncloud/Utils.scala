package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import com.btcontract.lncloud.ln.wire._

import org.slf4j.{Logger, LoggerFactory}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import rx.lang.scala.{Scheduler, Observable => Obs}
import com.btcontract.lncloud.Utils.{ListStr, OptString}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.crypto.RandomGenerator
import language.implicitConversions
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  type Bytes = Array[Byte]
  type ListStr = List[String]
  type OptString = Option[String]
  type BinaryDataList = List[BinaryData]
  type LightningMessages = List[LightningMessage]

  var values: Vals = _
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val hex2Json: String => String = raw => new String(HEX decode raw, "UTF-8")
  val logger: Logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val twoHours = 7200000

  implicit def str2BigInteger(bigInt: String): BigInteger = new BigInteger(bigInt)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def errLog: PartialFunction[Throwable, Unit] = { case err: Throwable => logger info err.getMessage }
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
  def none: PartialFunction[Any, Unit] = { case _ => }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)
}

object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler): Obs[T] =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  type IntervalPicker = (Throwable, Int) => Duration
  def pickInc(err: Throwable, next: Int): FiniteDuration = next.seconds
  def retry[T](obs: Obs[T], pick: IntervalPicker, times: Range): Obs[T] =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client

case class CacheItem[T](data: T, stamp: Long)
case class BlindData(tokens: ListStr, preimage: String, k: String)
case class Invoice(message: OptString, sum: MilliSatoshi, node: BinaryData, paymentHash: BinaryData)
case class Vals(privKey: BigInt, price: MilliSatoshi, quantity: Int, rpcUrl: String, zmqPoint: String, rewindRange: Int)
