package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import rx.lang.scala.{Scheduler, Observable => Obs}
import com.btcontract.lncloud.Utils.{ListStr, OptString}

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.crypto.RandomGenerator
import org.bitcoinj.params.TestNet3Params
import language.implicitConversions
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  type Bytes = Array[Byte]
  type ListStr = List[String]
  type OptString = Option[String]

  var values: Vals = _
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)

  val hex2Json: String => String = raw => new String(HEX decode raw, "UTF-8")
  val params: TestNet3Params = org.bitcoinj.params.TestNet3Params.get
  val logger: Logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val twoHours = 7200000

  implicit def str2BigInteger(bigInt: String): BigInteger = new BigInteger(bigInt)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
}

object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler): Obs[T] =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client
case class BlindData(tokens: ListStr, preimage: String, k: String)
case class Invoice(message: OptString, sum: MilliSatoshi, node: BinaryData, paymentHash: BinaryData)
case class Vals(privKey: BigInt, pubKeys: ListStr, price: MilliSatoshi, quantity: Int, rpcUrl: String)
case class CacheItem[T](data: T, stamp: Long)