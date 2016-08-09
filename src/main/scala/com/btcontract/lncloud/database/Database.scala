package com.btcontract.lncloud.database

import com.btcontract.lncloud.{BlindData, ServerSignedMail, WatchdogTx, Wrap}
import com.mongodb.WriteResult


abstract class Database {
  // Mapping from email to public key
  def putSignedMail(container: ServerSignedMail): WriteResult
  def getSignedMail(something: String): Option[ServerSignedMail]

  // sesPubKey is R which we get from k, rval is Lightning r-value
  def getPendingTokens(rval: String, sesPubKey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, sesPubKey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Messages
  def getAllWraps: Iterator[Wrap]
  def putWrap(wrap: Wrap)

  // Delayed transactions for broken channels
  def getDelayTxs(height: Int): Iterator[String]
  def putDelayTx(txHex: String, height: Int)
  def setDelayTxSpent(txHex: String)

  // Watchdog encrypted transactions
  def putWatchdogTx(parentTxId: String, txHex: String, ivHex: String)
  def getWatchdogTxs(parentTxId: String*): Seq[WatchdogTx]
  def setWatchdogTxsSpent(parentTxId: String*)
}