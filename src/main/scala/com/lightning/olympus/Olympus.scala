package com.lightning.olympus

import org.http4s.dsl._
import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, Transaction}
import org.http4s.{HttpService, Response}

import com.lightning.olympus.Router.ShortChannelIdSet
import com.lightning.olympus.database.MongoDatabase
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import com.lightning.wallet.ln.Tools.random
import fr.acinq.bitcoin.Crypto.PublicKey
import org.json4s.jackson.Serialization
import org.http4s.server.ServerApp
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import java.math.BigInteger


object Olympus extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments) = {

    args match {
      case List("testrun") =>
        values = Vals("33337641954423495759821968886025053266790003625264088739786982511471995762588",
          MilliSatoshi(2000000), 50, btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000",
          eclairApi = "http://213.133.99.89:8086", eclairSockIp = "213.133.99.89", eclairSockPort = 9096, rewindRange = 144 * 7,
          eclairNodeId = "03dc39d7f43720c2c0f86778dfd2a77049fa4a44b4f0a8afb62f3921567de41375",
          ip = "127.0.0.1", checkByToken = true)

      case List("production", rawVals) =>
        values = toClass[Vals](rawVals)
    }

    LNParams.setup(random getBytes 32)
    val httpLNCloudServer = new Responder
    val postLift = UrlFormLifter(httpLNCloudServer.http)
    BlazeBuilder.bindHttp(9001, values.ip).mountService(postLift).start
  }
}

class Responder { me =>
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]
  private val exchangeRates = new ExchangeRates
  private val blindTokens = new BlindTokens
  private val feeRates = new FeeRates
  private val db = new MongoDatabase
  private val V1 = Root / "v1"
  private val BODY = "body"

  // Watching chain and socket
  new ListenerManager(db).connect

  private val check =
    values.checkByToken match {
      case true => new BlindTokenChecker
      case false => new SignatureChecker
    }

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> V1 / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens and send an Invoice
    case req @ POST -> V1 / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val prunedTokens = toClass[StringSeq](hex2Ascii apply tokens) take values.quantity

      val requestInvoice = for {
        pkItem <- blindTokens.cache get sesKey
        request = blindTokens generateInvoice values.price
        blind = BlindData(request.paymentHash, pkItem.data, prunedTokens)
      } yield {
        db.putPendingTokens(blind, sesKey)
        ok(PaymentRequest write request)
      }

      requestInvoice match {
        case Some(invoice) => Ok apply invoice
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      // We only sign tokens if the request has been paid

      val blindSignatures = for {
        blindData <- db.getPendingTokens(req params "seskey")
        if db isPaymentFulfilled blindData.paymentHash

        bigInts = for (blindToken <- blindData.tokens) yield new BigInteger(blindToken)
      } yield bigInts.map(bigInt => blindTokens.signer.blindSign(bigInt, blindData.k).toString)

      blindSignatures match {
        case Some(sigs) => Ok apply ok(sigs:_*)
        case None => Ok apply error("notfound")
      }

    // ROUTER

    case req @ POST -> V1 / "router" / "routes"
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes" =>
      val Seq(nodes, channels, from, to) = extract(req.params, identity, "nodes", "channels", "from", "to")
      val withoutNodeIds = toClass[StringSeq](hex2Ascii apply nodes).toSet take 100 map string2PublicKey
      val withoutShortChannelIds = toClass[ShortChannelIdSet](hex2Ascii apply channels) take 100
      val paths = Router.finder.safeFindPaths(withoutNodeIds, withoutShortChannelIds, from, to)
      val encoded = for (hops <- paths) yield hops.map(hopCodec.encode(_).require.toHex)
      Ok apply ok(encoded:_*)

    case req @ POST -> V1 / "router" / "nodes" =>
      val query = req.params("query").trim.take(32).toLowerCase
      // A node may be well connected but not public and thus having no node announcement
      val announces = if (query.nonEmpty) Router.maps.searchTrie.getValuesForKeysStartingWith(query).asScala
        else Router.maps.nodeId2Chans.seq take 48 flatMap { case key \ _ => Router.maps.nodeId2Announce get key }

      // Json4s serializes tuples as maps while we need lists so we explicitly fix that here
      val encoded = announces.take(24).map(ann => nodeAnnouncementCodec.encode(ann).require.toHex)
      val sizes = announces.take(24).map(ann => Router.maps.nodeId2Chans.nodeMap(ann.nodeId).size)
      val fixed = encoded zip sizes map { case enc \ size => enc :: size :: Nil }
      Ok apply ok(fixed.toList:_*)

    // TRANSACTIONS

    case req @ POST -> V1 / "txs" / "get" =>
      // Given a list of commit tx ids, fetch all child txs which spend their outputs
      val txids = req.params andThen hex2Ascii andThen toClass[StringSeq] apply "txids"
      val spendTxs = db.getTxs(txids take 20)
      Ok apply ok(spendTxs:_*)

    case req @ POST -> V1 / "txs" / "schedule" => check.verify(req.params) {
      val txs = req.params andThen hex2Ascii andThen toClass[StringSeq] apply BODY
      for (raw <- txs) db.putScheduled(Transaction read raw)
      Ok apply ok("done")
    }

    // ARBITRARY DATA

    case req @ POST -> V1 / "data" / "put" => check.verify(req.params) {
      val Seq(key, userDataHex) = extract(req.params, identity, "key", BODY)
      db.putData(key, prefix = userDataHex take 64, userDataHex)
      Ok apply ok("done")
    }

    case req @ POST -> V1 / "data" / "get" =>
      val results = db.getData(req params "key")
      Ok apply ok(results:_*)

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / _ / "rates" / "get" =>
      val feeEstimates = feeRates.rates.mapValues(_ getOrElse 0)
      val exchanges = exchangeRates.currencies.map(c => c.code -> c.average)
      val processedExchanges = exchanges.toMap.mapValues(_ getOrElse 0)
      val result = feeEstimates :: processedExchanges :: Nil
      Ok apply ok(result)

    case GET -> Root / _ / "rates" / "state" =>
      Ok(exchangeRates.displayState mkString "\r\n\r\n")

    // NEW VERSION WARNING AND TESTS

    case req @ POST -> Root / _ / "check" => check.verify(req.params) {
      // This is a test where we simply check if a user supplied data is ok
      Ok apply ok("done")
    }

    case POST -> Root / "v2" / _ =>
      Ok apply error("mustupdate")
  }

  // HTTP answer as JSON array
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data

  trait DataChecker {
    // Incoming data may be accepted either by signature or blind token
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse
  }

  class BlindTokenChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(point, cleartoken, clearsig) = extract(params, identity, "point", "cleartoken", "clearsig")
      lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
        clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

      if (params(BODY).length > 250000) Ok apply error("bodytoolarge")
      else if (db isClearTokenUsed cleartoken) Ok apply error("tokenused")
      else if (!signatureIsFine) Ok apply error("tokeninvalid")
      else try next finally db putClearToken cleartoken
    }
  }

  class SignatureChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(data, sig, key) = extract(params, BinaryData.apply, BODY, "sig", "key")
      lazy val sigOk = Crypto.verifySignature(Crypto sha256 data, sig, PublicKey apply key)

      val userPubKeyIsPresent = db keyExists key.toString
      if (params(BODY).length > 250000) Ok apply error("bodytoolarge")
      else if (!userPubKeyIsPresent) Ok apply error("keynotfound")
      else if (!sigOk) Ok apply error("siginvalid")
      else next
    }
  }
}