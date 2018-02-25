package com.lightning.olympus

import spray.json._
import org.http4s.dsl._
import fr.acinq.bitcoin._
import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import spray.json.DefaultJsonProtocol._
import scala.collection.JavaConverters._
import com.lightning.wallet.lnutils.ImplicitJsonFormats._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import org.http4s.{HttpService, Response}

import com.lightning.olympus.Router.ShortChannelIdSet
import com.lightning.olympus.database.MongoDatabase
import org.http4s.server.middleware.UrlFormLifter
import com.lightning.olympus.JsonHttpUtils.to
import org.http4s.server.blaze.BlazeBuilder
import com.lightning.wallet.ln.Tools.random
import fr.acinq.bitcoin.Crypto.PublicKey
import language.implicitConversions
import org.http4s.server.ServerApp
import org.bitcoinj.core.ECKey
import scalaz.concurrent.Task
import java.math.BigInteger


object Olympus extends ServerApp {
  type ProgramArguments = List[String]
  def server(args: ProgramArguments) = {

    args match {
      case List("testrun") =>
        values = Vals(privKey = "33337641954423495759821968886025053266790003625264088739786982511471995762588",
          MilliSatoshi(2000000), 50, btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000",
          eclairApi = "http://213.133.99.89:8080", eclairSockIp = "213.133.99.89", eclairSockPort = 9735, rewindRange = 7,
          eclairNodeId = "03dc39d7f43720c2c0f86778dfd2a77049fa4a44b4f0a8afb62f3921567de41375", eclairPass = "pass",
          ip = "127.0.0.1", checkByToken = true)

//        values = Vals("33337641954423495759821968886025053266790003625264088739786982511471995762588",
//          MilliSatoshi(2000000), 50, btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000",
//          eclairApi = "http://127.0.0.1:8080", eclairSockIp = "127.0.0.1", eclairSockPort = 9735, rewindRange = 1,
//          eclairNodeId = "0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3", eclairPass = "pass",
//          ip = "127.0.0.1", checkByToken = true)

      case List("production", rawVals) =>
        values = to[Vals](rawVals)
    }

    LNParams.setup(random getBytes 32)
    val httpLNCloudServer = new Responder
    val postLift = UrlFormLifter(httpLNCloudServer.http)
    BlazeBuilder.bindHttp(9002, values.ip).mountService(postLift).start
  }
}

class Responder { me =>
  type TaskResponse = Task[Response]
  type HttpParams = Map[String, String]

  implicit def js2Task(js: JsValue): TaskResponse = Ok(js.toString)
  private val (bODY, oK, eRROR) = Tuple3("body", "ok", "error")
  private val exchangeRates = new ExchangeRates
  private val blindTokens = new BlindTokens
  private val feeRates = new FeeRates
  private val db = new MongoDatabase

  // Watching chain and socket
  new ListenerManager(db).connect
  Blockchain.rescanBlocks

  private val check =
    values.checkByToken match {
      case true => new BlindTokenChecker
      case false => new SignatureChecker
    }

  val http = HttpService {
    // Put an EC key into temporal cache and provide SignerQ, SignerR (seskey)
    case POST -> Root / "blindtokens" / "info" => new ECKey(random) match { case ses =>
      blindTokens.cache(ses.getPublicKeyAsHex) = CacheItem(ses.getPrivKey, System.currentTimeMillis)
      val response = Tuple3(blindTokens.signer.masterPubKeyHex, ses.getPublicKeyAsHex, values.quantity)
      Tuple2(oK, response).toJson
    }

    // Record tokens and send an Invoice
    case req @ POST -> Root / "blindtokens" / "buy" =>
      val Seq(sesKey, tokens) = extract(req.params, identity, "seskey", "tokens")
      val pruned = hex2Ascii andThen to[StringVec] apply tokens take values.quantity

      blindTokens.cache get sesKey map { item =>
        val request = blindTokens generateInvoice values.price
        val blind = BlindData(request.paymentHash, item.data, pruned)
        db.putPendingTokens(blind, sesKey)
        PaymentRequest write request
      } match {
        case Some(invoice) => Tuple2(oK, invoice).toJson
        case None => Tuple2(eRROR, "notfound").toJson
      }

    // Provide signed blind tokens
    case req @ POST -> Root / "blindtokens" / "redeem" =>
      // We only sign tokens if the request has been fulfilled

      db.getPendingTokens(req params "seskey") match {
        case Some(data) if blindTokens isFulfilled data =>
          Tuple2(oK, blindTokens sign data).toJson

        case None => Tuple2(eRROR, "notfound").toJson
        case _ => Tuple2(eRROR, "notfulfilled").toJson
      }

    // ROUTER

    case req @ POST -> Root / "router" / "routes" =>
      val Seq(xnodes, xchans, froms, tos) = extract(req.params, hex2Ascii, "xn", "xc", "froms", "tos")
      val paths = Router.finder.findPaths(xNodes = to[StringSet](xnodes) take 250 map string2PublicKey,
        xChans = to[ShortChannelIdSet](xchans) take 500, to[StringVec](froms) take 8 map string2PublicKey,
        destination = to[StringSet](tos).head)

      Tuple2(oK, paths).toJson

    case req @ POST -> Root / "router" / "nodes" =>
      val query = req.params("query").trim.take(32).toLowerCase
      // A node may be well connected but not public and thus having no node announcement
      val announces = if (query.nonEmpty) Router.searchTrie.getValuesForKeysStartingWith(query).asScala
        else Router.nodeId2Chans.defaultSuggestions take 48 flatMap Router.nodeId2Announce.get

      val encoded = announces.take(24).map(ann => nodeAnnouncementCodec.encode(ann).require.toHex)
      val sizes = announces.take(24).map(ann => Router.nodeId2Chans.dict(ann.nodeId).size)
      Tuple2(oK, encoded zip sizes).toJson

    // TRANSACTIONS AND BLOCKS

    case req @ POST -> Root / "block" / "get" =>
      val block = bitcoin.getBlock(req params "hash")
      val data = block.height -> block.tx.asScala.toVector
      Tuple2(oK, data).toJson

    case req @ POST -> Root / "txs" / "get" =>
      // Given a list of commit tx ids, fetch all child txs which spend their outputs
      val txIds = req.params andThen hex2Ascii andThen to[StringVec] apply "txids" take 24
      Tuple2(oK, db getTxs txIds).toJson

    case req @ POST -> Root / "txs" / "schedule" => check.verify(req.params) {
      val txs = req.params andThen hex2Ascii andThen to[StringVec] apply bODY
      for (raw <- txs) db.putScheduled(Transaction read raw)
      Tuple2(oK, "done").toJson
    }

    // ARBITRARY DATA

    case req @ POST -> Root / "data" / "put" => check.verify(req.params) {
      val Seq(key, userDataHex) = extract(req.params, identity, "key", bODY)
      db.putData(key, prefix = userDataHex take 32, userDataHex)
      Tuple2(oK, "done").toJson
    }

    case req @ POST -> Root / "data" / "get" =>
      val results = db.getData(req params "key")
      Tuple2(oK, results).toJson

    // FEERATE AND EXCHANGE RATES

    case POST -> Root / "rates" / "get" =>
      val feesPerBlock = for (k \ v <- feeRates.rates) yield (k.toString, v getOrElse 0D)
      val fiatRates = for (cur <- exchangeRates.currencies) yield (cur.code, cur.average)
      val response = Tuple2(feesPerBlock.toMap, fiatRates.toMap)
      Tuple2(oK, response).toJson

    case GET -> Root / "rates" / "state" => Ok(exchangeRates.displayState mkString "\r\n\r\n")
    case req @ POST -> Root / "check" => check.verify(req.params)(Tuple2(oK, "done").toJson)
  }

  trait DataChecker {
    // Incoming data may be accepted either by signature or blind token
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse
  }

  class BlindTokenChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(point, cleartoken, clearsig) = extract(params, identity, "point", "cleartoken", "clearsig")
      lazy val signatureIsFine = blindTokens.signer.verifyClearSig(clearMsg = new BigInteger(cleartoken),
        clearSignature = new BigInteger(clearsig), point = blindTokens decodeECPoint point)

      if (params(bODY).length > 250000) Tuple2(eRROR, "bodytoolarge").toJson
      else if (db isClearTokenUsed cleartoken) Tuple2(eRROR, "tokenused").toJson
      else if (!signatureIsFine) Tuple2(eRROR, "tokeninvalid").toJson
      else try next finally db putClearToken cleartoken
    }
  }

  class SignatureChecker extends DataChecker {
    def verify(params: HttpParams)(next: => TaskResponse): TaskResponse = {
      val Seq(data, sig, pubkey) = extract(params, BinaryData.apply, bODY, "sig", "pubkey")
      lazy val sigOk = Crypto.verifySignature(Crypto sha256 data, sig, PublicKey apply pubkey)

      val userPubKeyIsPresent = db keyExists pubkey.toString
      if (params(bODY).length > 250000) Tuple2(eRROR, "bodytoolarge").toJson
      else if (!userPubKeyIsPresent) Tuple2(eRROR, "keynotfound").toJson
      else if (!sigOk) Tuple2(eRROR, "siginvalid").toJson
      else next
    }
  }
}