package com.btcontract.lncloud

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.crypto.RandomGenerator
import org.slf4j.LoggerFactory
import java.math.BigInteger

import org.bitcoinj.core.Utils.HEX
import com.btcontract.lncloud.Utils.Bytes
import courier.Mailer
import org.bitcoinj.core.{ECKey, Sha256Hash, Transaction}


object Utils {
  type Bytes = Array[Byte]

  var values: Vals = null
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val logger = LoggerFactory getLogger "LNCloud"
  val rand = new RandomGenerator
  val oneDay = 86400000
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client
case class BlindData(tokens: Seq[String], rval: String, k: String) {
  def tokensBigInts = for (token <- tokens) yield new BigInteger(token)
  def kBigInt = new BigInteger(k)
}

// A "response-to" ephemeral key, it's private part should be stored in a database
// because my bloom filter has it, it's optional because Charge may come locally via NFC
case class Request(ephemeral: Option[Bytes], mSatAmount: Long, message: String, id: String)
case class Charge(request: Request, lnPaymentData: Bytes)

// Clients send a message and server adds a timestamp
case class Message(pubKey: Bytes, content: Bytes)
case class Wrap(data: Message, stamp: Long)

// Client and server signed email to key mappings
case class ServerSignedMail(client: SignedMail, signature: String)
case class SignedMail(email: String, pubKey: String, signature: String) {
  def totalHash = Sha256Hash.of(email + pubKey + signature getBytes "UTF-8")
  def identityPubECKey = ECKey.fromPublicOnly(HEX decode pubKey)
  def emailHash = Sha256Hash.of(email getBytes "UTF-8")
}

// Utility classes
case class CacheItem[T](data: T, stamp: Long)
case class BlindParams(privKey: BigInteger, quantity: Int, price: Long)
case class EmailParams(server: String, account: String, password: String) {
  def mailer = Mailer(server, 587).auth(true).as(account, password).startTtls(true).apply
}

case class WatchdogTx(txHex: String, ivHex: String) {
  def decode(tx: Transaction) = ???
}

// Server secrets and parameters, MUST NOT be stored in config file
case class Vals(emailParams: EmailParams, emailPrivKey: BigInteger, blindParams: BlindParams,
                storagePeriod: Int, sockIpLimit: Int, maxMessageSize: Int, rpcUrl: String)