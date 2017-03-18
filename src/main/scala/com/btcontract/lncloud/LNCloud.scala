package com.btcontract.lncloud

import Utils._
import com.btcontract.lncloud.ln.wire.Codecs.{PaymentRoute, announcements, hops}
import com.btcontract.lncloud.router.Router
import org.http4s.dsl._
import org.http4s.{HttpService, Response}
import org.http4s.server.{Server, ServerApp}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, string2binaryData}
import collection.JavaConverters._
import concurrent.ExecutionContext.Implicits.global
import org.http4s.server.middleware.UrlFormLifter
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.jackson.Serialization
import scodec.Attempt.Successful
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import database.MongoDatabase


object LNCloud extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments): Task[Server] = {
    val config = Vals(new ECKey(rand).getPrivKey, MilliSatoshi(500000), 100,
      "http://user:password@127.0.0.1:8332", "tcp://127.0.0.1:28332", 144)

    values = config /*toClass[Vals](config)*/
    val socketAndHttpLnCloudServer = new Responder
    val postLift = UrlFormLifter(socketAndHttpLnCloudServer.http)
    BlazeBuilder.bindHttp(9002).mountService(postLift).start
  }
}

class Responder {
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  private val db: MongoDatabase = null// = new MongoDatabase
  private val blindTokens = new BlindTokens(db)
  private val V1 = Root / "v1"

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> V1 / "blindtokens" / "info" => new ECKey(rand) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      Ok apply ok(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
    }

    // Record tokens and send an Invoice
    case req @ POST -> V1 / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val prunedTokens = toClass[ListStr](hex2Json apply tokens) take values.quantity
      val maybeInvoice = blindTokens.getInvoice(prunedTokens, sesKey)

      maybeInvoice match {
        case Some(future) => Ok(future map okSingle)
        case _ => Ok apply error("notfound")
      }

    // Provide signed blind tokens
    case req @ POST -> V1 / "blindtokens" / "redeem" =>
      val payHash = BinaryData(req params "hash")

      val res = blindTokens isFulfilled payHash map {
        case true => blindTokens signTokens payHash match {
          case Some(blindSignatures) => ok(blindSignatures:_*)
          case None => error("notfound")
        }

        case false => error("notpaid")
      } recover { case err: Throwable =>
        logger info err.getMessage
        error("nodefail")
      }

      Ok apply res

    // BREACH TXS

    // If they try to supply way too much data
    case req @ POST -> V1 / "token" / "tx" / "breach"
      if req.params("watch").length > 2048 =>
      Ok apply error("toobig")

    // Record a transaction to be broadcasted in case of channel breach
    case req @ POST -> V1 / "token" / "tx" / "breach" => ifToken(req.params) {
      Ok apply okSingle("done")
    }

    // DATA STORAGE

    // If they try to supply way too much data
    case req @ POST -> V1 / "token" / "data" / "put"
      if req.params("data").length > 2048 =>
      Ok apply error("toobig")

    case req @ POST -> V1 / "token" / "data" / "put" => ifToken(req.params) {
      // Rewrites user's channel data, but also can be used for general purposes
      db.putGeneralData(req params "key", req params "data")
      Ok apply okSingle("done")
    }

    case req @ POST -> V1 / "data" / "get" =>
      db.getGeneralData(key = req params "key") match {
        case Some(realData) => Ok apply okSingle(realData)
        case _ => Ok apply error("notfound")
      }

    case req @ POST -> V1 / "data" / "delete" =>
      db.deleteGeneralData(req params "key")
      Ok apply okSingle("done")

    // ROUTER DATA

    case req @ POST -> V1 / "router" / "routes"
      if Router.black.contains(req params "from") =>
      Ok apply error("fromblacklisted")

    case req @ POST -> V1 / "router" / "routes"
      if Router.channels.nodeId2Chans(req params "to").isEmpty =>
      Ok apply error("tolost")

    case req @ POST -> V1 / "router" / "routes" =>
      val routes = Router.finder.findRoutes(req params "from", req params "to")
      val data = routes take 8 map hops.encode collect { case Successful(bv) => bv.toHex }
      Ok apply ok(data:_*)

    case POST -> V1 / "router" / "nodes" / "list" =>
      val nodes = Router.nodes.id2Node.values take 20
      val result = announcements encode nodes.toList

      result match {
        case Successful(bv) => Ok apply ok(bv.toHex)
        case _ => Ok apply error("notfound")
      }

    case req @ POST -> V1 / "router" / "nodes" / "find" =>
      val query = req.params("query").trim.take(50).toLowerCase
      val nodes = Router.nodes.searchTree getValuesForKeysStartingWith query
      val result = announcements encode nodes.asScala.toList.take(20)

      result match {
        case Successful(bv) => Ok apply ok(bv.toHex)
        case _ => Ok apply error("notfound")
      }

    // NEW VERSION WARNING

    case POST -> Root / "v2" / _ =>
      Ok apply error("mustupdate")
  }

  // Checking clear token validity before proceeding
  def ifToken(params: HttpParams)(next: => TaskResponse): TaskResponse =  {
    val Seq(point, sig, token) = extract(params, identity, "point", "clearsig", "cleartoken")
    val sigIsFine = blindTokens.signer.verifyClearSig(token, sig, blindTokens decodeECPoint point)

    if (db isClearTokenUsed token) Ok apply error("tokenused")
    else if (!sigIsFine) Ok apply error("tokeninvalid")
    else try next finally db putClearToken token
  }

  // HTTP answer as JSON array
  def okSingle(data: Any): String = ok(data)
  def ok(data: Any*): String = Serialization write "ok" +: data
  def error(data: Any*): String = Serialization write "error" +: data
}