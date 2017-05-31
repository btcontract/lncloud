package com.btcontract.lncloud

import org.http4s.dsl._
import fr.acinq.bitcoin._
import collection.JavaConverters._
import com.btcontract.lncloud.Utils._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._

import org.http4s.server.{Server, ServerApp}
import org.http4s.{HttpService, Response}

import com.lightning.wallet.ln.wire.NodeAnnouncement
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.jackson.Serialization
import com.lightning.wallet.ln.Invoice
import scodec.Attempt.Successful
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import database.MongoDatabase
import java.math.BigInteger


object LNCloud extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments): Task[Server] = {
    val config = Vals(new ECKey(random).getPrivKey, MilliSatoshi(500000), 50,
      btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000",
      eclairApi = "http://127.0.0.1:8081", eclairIp = "127.0.0.1", eclairPort = 48001,
      eclairNodeId = "032e790e58b944265b56cf15c0b9955c9cf844f9e3a668c1aa23cc4c78b781ae86",
      rewindRange = 144, checkByToken = true)

    values = config
    RouterConnector.socket.start
    val socketAndHttpLnCloudServer = new Responder
    val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
    BlazeBuilder.bindHttp(9002).mountService(postLift).start
  }
}

class Responder {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]
  private val blindTokens = new BlindTokens
  private val exchangeRates = new ExchangeRates
  private val feeRates = new FeeRates
  private val db = new MongoDatabase
  private val V1 = Root / "v1"
  private val dummy = "unknown"
  private val body = "body"

  private val check =
    // How an incoming data should be checked
    if (values.checkByToken) new BlindTokenChecker
    else new SignatureChecker

  private val convertNodes: Seq[NodeAnnouncement] => TaskResponse = nodes => {
    val announces: Seq[String] = nodes.map(nodeAnnouncementCodec.encode(_).require.toHex)
    val channelsPerNode = nodes.map(announce => Router.maps.nodeId2Chans(announce.nodeId).size)
    val result = announces zip channelsPerNode map { case (announce, chans) => announce :: chans :: Nil }
    Ok apply ok(result:_*)
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
      val prunedTokens = toClass[StringSeq](hex2Json apply tokens) take values.quantity
      // We put request details in a db and provide an invoice for them to fulfill

      val requestInvoice = for {
        privateKey <- blindTokens.cache get sesKey
        invoice = blindTokens generateInvoice values.price
        blind = BlindData(invoice, privateKey.data, prunedTokens)
      } yield {
        db.putPendingTokens(blind, sesKey)
        okSingle(Invoice serialize invoice)
      }

      requestInvoice match {
        case Some(invoice) => Ok apply invoice
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      // We only sign tokens if the request has been paid

      val blindSignatures = for {
        blindData <- db getPendingTokens req.params("seskey")
        if blindTokens isFulfilled blindData.invoice.paymentHash
        bigInts = for (blindToken <- blindData.tokens) yield new BigInteger(blindToken)
        signatures = for (bi <- bigInts) yield blindTokens.signer.blindSign(bi, blindData.k).toString
      } yield signatures

      blindSignatures match {
        case Some(sigs) => Ok apply ok(sigs:_*)
        case None => Ok apply error("notfound")
      }

    // ROUTER

    case req @ POST -> V1 / "router" / "routes"
      // GUARD: counterparty has been blacklisted
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes" =>
      val routes = Router.finder.findRoutes(req params "from", req params "to").sortBy(_.size)
      val data = routes take 10 map hopsCodec.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    case req @ POST -> V1 / "router" / "nodes" if req.params("query").isEmpty =>
      // Initially there is no query so we show a bunch of totally random nodes to user
      val candidates: Seq[NodeAnnouncement] = Router.maps.nodeId2Announce.values.toVector
      val Tuple2(resultSize, colSize) = Tuple2(math.min(25, candidates.size), candidates.size)
      convertNodes(Vector.fill(resultSize)(random nextInt colSize) map candidates)

    case req @ POST -> V1 / "router" / "nodes" =>
      val query: String = req.params("query").trim.take(50).toLowerCase
      val nodes = Router.maps.searchTrie getValuesForKeysStartingWith query
      convertNodes(nodes.asScala.toList take 25)

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / _ / "feerates" =>
      val result = feeRates.rates.mapValues(_ getOrElse dummy)
      Ok apply ok(Serialization write result)

    case POST -> Root / _ / "exchangerates" =>
      val computed = exchangeRates.currencies.map(c => c.code -> c.average)
      val result = computed.toMap.mapValues(_ getOrElse dummy)
      Ok apply ok(Serialization write result)

    case GET -> Root / "exchangerates" / "state" =>
      Ok(exchangeRates.displayState mkString "\r\n\r\n")

    // NEW VERSION WARNING AND TESTS

    case req @ POST -> Root / _ / "sigcheck" => check.verify(req.params) {
      // This is a test where we simply check if a user supplied key is valid
      Ok apply ok("done")
    }

    case req @ POST -> Root / _ / "ping" => Ok apply ok(req params body)
    case POST -> Root / "v2" / _ => Ok apply error("mustupdate")
  }

  // HTTP answer as JSON array
  def okSingle(data: Any): String = ok(data)
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data

  // Ckecking if incoming data can be accepted either by signature or blind token
  trait DataChecker { def verify(params: HttpParams)(next: => TaskResponse): TaskResponse }

  class BlindTokenChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(point, clearsig, cleartoken) = extract(params, identity, "point", "clearsig", "cleartoken")
      lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
        clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

      if (params(body).length > 8192) Ok apply error("bodytoolarge")
      else if (db isClearTokenUsed cleartoken) Ok apply error("tokenused")
      else if (!signatureIsFine) Ok apply error("tokeninvalid")
      else try next finally db putClearToken cleartoken
    }
  }

  class SignatureChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(data, sig, key) = extract(params, BinaryData.apply, body, "sig", "pubkey")
      lazy val signatureIsFine = Crypto.verifySignature(Crypto sha256 data, sig, key)

      val userPubKeyIsPresent = db keyExists key.toString
      if (params(body).length > 8192) Ok apply error("bodytoolarge")
      else if (!userPubKeyIsPresent) Ok apply error("keynotfound")
      else if (!signatureIsFine) Ok apply error("siginvalid")
      else next
    }
  }
}