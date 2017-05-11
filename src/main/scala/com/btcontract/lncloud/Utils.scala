package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import com.lightning.wallet.ln.{Invoice, Tools}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import rx.lang.scala.{Scheduler, Observable => Obs}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.Utils.StringSeq
import fr.acinq.bitcoin.Crypto.PublicKey
import language.implicitConversions
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  var values: Vals = _
  type StringSeq = Seq[String]

  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val hex2Json: String => String = raw => new String(HEX decode raw, "UTF-8")
  val random = new com.lightning.wallet.ln.crypto.RandomGenerator
  val twoHours = 7200000

  implicit def binData2PublicKey(data: BinaryData): PublicKey = PublicKey(data)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
  def errLog: PartialFunction[Throwable, Unit] = { case err => Tools log err.getMessage }
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
case class BlindData(invoice: Invoice, k: BigInteger, tokens: StringSeq)
case class Vals(privKey: BigInt, price: MilliSatoshi, quantity: Int, rpcUrl: String,
                zmqPoint: String, eclairApi: String, eclairIp: String, eclairPort: Int,
                eclairNodeId: BinaryData, rewindRange: Int, checkByToken: Boolean)