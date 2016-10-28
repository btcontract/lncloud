package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import rx.lang.scala.{Scheduler, Observable => Obs}
import com.btcontract.lncloud.Utils.{Bytes, ListStr}
import com.btcontract.lncloud.crypto.{AES, RandomGenerator}
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import org.bitcoinj.core.Utils.HEX
import org.slf4j.LoggerFactory
import java.math.BigInteger


object Utils {
  type Bytes = Array[Byte]
  type ListStr = List[String]

  var values: Vals = _
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val hex2Json = (raw: String) => new String(HEX decode raw, "UTF-8")
  val logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val oneHour = 3600000

  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*) = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*) = args.map(src andThen fn)
  def toClass[T : Manifest](raw: String) = parse(raw, useBigDecimalForDouble = true).extract[T]
}

object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler) =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)
}

case class BlindData(tokens: Seq[String], rval: String, k: String) {
  // tokens is a list of yet unsigned blind BigInts provided from client
  // k is session private key, a source for signerR
  val kBigInt = new BigInteger(k)
}

// Prefix is first 16 bytes of txId, suffix is last 16 bytes
case class WatchdogTx(prefix: String, txEnc: String, iv: String) {
  def decodeTx(suffix: Bytes) = AES.dec(HEX decode txEnc, suffix, HEX decode iv)
}

case class MSat(amt: Long)
case class Invoice(message: Option[String], amount: MSat, node: String, rHash: Bytes)
case class Vals(privKey: BigInteger, pubKeys: ListStr, price: MSat, quantity: Int, rpcUrl: String)
case class CacheItem[T](data: T, stamp: Long)