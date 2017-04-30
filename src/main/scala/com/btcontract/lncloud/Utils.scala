package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import rx.lang.scala.{Scheduler, Observable => Obs}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.Utils.TokenSeq
import com.lightning.wallet.ln.Invoice
import fr.acinq.bitcoin.MilliSatoshi
import language.implicitConversions
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  var values: Vals = _
  type TokenSeq = Seq[String]

  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val hex2Json: String => String = raw => new String(HEX decode raw, "UTF-8")
  val random = new com.lightning.wallet.ln.crypto.RandomGenerator
  val twoHours = 7200000

  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
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
case class BlindData(invoice: Invoice, k: BigInteger, tokens: TokenSeq)
case class Vals(privKey: BigInt, price: MilliSatoshi, quantity: Int, rpcUrl: String,
                eclairUrl: String, zmqPoint: String, rewindRange: Int)