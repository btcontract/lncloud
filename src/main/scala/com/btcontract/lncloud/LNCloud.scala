package com.btcontract.lncloud

import Utils._
import org.http4s.dsl._
import collection.JavaConverters._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, string2binaryData}
import org.http4s.server.{Server, ServerApp}
import org.http4s.{HttpService, Response}

import com.lightning.wallet.ln.wire.NodeAnnouncement
import concurrent.ExecutionContext.Implicits.global
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import com.btcontract.lncloud.router.Router
import fr.acinq.bitcoin.Crypto.PublicKey
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
    val config = Vals(new ECKey(random).getPrivKey, MilliSatoshi(500000), quantity = 50,
      rpcUrl = "http://user:password@127.0.0.1:8332", eclairUrl = "http://127.0.0.1:8080",
      zmqPoint = "tcp://127.0.0.1:28332", rewindRange = 144, checkByToken = true)

    values = config /*toClass[Vals](config)*/
    val socketAndHttpLnCloudServer = new Responder
    val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
    BlazeBuilder.bindHttp(9002).mountService(postLift).start
  }
}

class Responder {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  private val body = "body"
  private val V1 = Root / "v1"
  private val db = new MongoDatabase
  private val blindTokens = new BlindTokens

  private val check =
    // How an incoming data should be checked
    if (values.checkByToken) new BlindTokenChecker
    else new SignatureChecker

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

      // Only if we have a seskey in a cache
      blindTokens.cache get sesKey map { privateKey =>
        val blindFuture = blindTokens.makeBlind(prunedTokens, privateKey.data)
        for (blindData <- blindFuture) db.putPendingTokens(data = blindData, sesKey)
        for (blindData <- blindFuture) yield okSingle(Invoice serialize blindData.invoice)
      } match {
        case Some(future) => Ok apply future
        case None => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      // Only if we have a saved BlindData which is paid for
      db.getPendingTokens(req params "seskey") map { blindData =>
        blindTokens isFulfilled blindData.invoice.paymentHash map {
          case true => ok(data = blindTokens signTokens blindData:_*)
          case false => error("notpaid")
        }
      } match {
        case Some(future) => Ok apply future
        case None => Ok apply error("notfound")
      }

    // BREACH TXS

    case req @ POST -> V1 / "tx" / "watch" => check.verify(req.params) {
      // Record a transaction to be broadcasted in case of channel breach
      Ok apply okSingle("done")
    }

    // DATA STORAGE

    case req @ POST -> V1 / "data" / "put" => check.verify(req.params) {
      // Rewrites user's channel data but can be used for general purposes
      db.putGeneralData(req params "key", req params body)
      Ok apply okSingle("done")
    }

    case req @ POST -> V1 / "data" / "get" =>
      db.getGeneralData(req params "key") match {
        case Some(result) => Ok apply okSingle(result)
        case _ => Ok apply error("notfound")
      }

    // ROUTER

    case req @ POST -> V1 / "router" / "routes"
      // GUARD: counterparty has been blacklisted
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes"
      // GUARD: destination has been blacklisted or removed
      if Router.channels.nodeId2Chans(req params "to").isEmpty =>
      Ok apply error("tolost")

    case req @ POST -> V1 / "router" / "routes" =>
      val routes: Seq[PaymentRoute] = Router.finder.findRoutes(req params "from", req params "to")
      val data = routes take 5 map hopsCodec.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    case req @ POST -> V1 / "router" / "nodes" / "find" =>
      val query = req.params("query").trim.take(50).toLowerCase
      val nodes: Seq[NodeAnnouncement] = Router.nodes.searchTree.getValuesForKeysStartingWith(query).asScala.toList
      val data = nodes take 20 map nodeAnnouncementCodec.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    // NEW VERSION WARNING AND TEST

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
      val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
        clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

      if (params(body).length > 8192) Ok apply error("bodytoolarge")
      else if (db isClearTokenUsed cleartoken) Ok apply error("tokenused")
      else if (!signatureIsFine) Ok apply error("tokeninvalid")
      else try next finally db putClearToken cleartoken
    }
  }

  class SignatureChecker extends DataChecker {
    private val pubKeys = db.getPublicKeys map BinaryData.apply map PublicKey.apply
    private val keysMap = pubKeys.map(key => key.toString.take(8) -> key).toMap

    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(data, sig, prefix) = extract(params, identity, body, "sig", "prefix")
      val signatureIsFine = Crypto.verifySignature(BinaryData(data), BinaryData(sig),
        publicKey = keysMap apply prefix)

      if (params(body).length > 8192) Ok apply error("bodytoolarge")
      else if (!signatureIsFine) Ok apply error("signatureinvalid")
      else next
    }
  }
}