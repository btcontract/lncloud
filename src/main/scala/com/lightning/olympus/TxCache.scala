package com.lightning.olympus

import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.collection.JavaConverters._
import rx.lang.scala.{Observable => Obs}

import com.lightning.olympus.JsonHttpUtils.to
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Tools.log
import com.lightning.olympus.Utils.bitcoin
import com.google.common.base.Charsets
import com.google.common.io.Files
import scala.collection.mutable
import io.Source.fromFile
import scala.util.Try


object TxCache { me =>
  type TxList = List[String]
  type BlockHeightToTxs = (Int, TxList)
  type BlockTxCache = List[BlockHeightToTxs]

  final val step = 1000
  final val startBlock = 505000
  val txCache = new mutable.HashMap[Int, TxList]

  def fileName(height: Int) = s"txcache/b-$height-${height + step - 1}.json"
  // Too many entries in txCache will cause memory overflow so clean it up periodically here
  def clear = if (txCache.size > step * 5) txCache --= txCache.keys.toVector.sorted.dropRight(step)
  Obs interval 10.seconds foreach { _ => clear }

  // SAVING

  def getRangeList(height: Int) = for {
    midHeight <- height until height + step
    txsBuffer = bitcoin.getBlock(midHeight).tx
  } yield midHeight -> txsBuffer.asScala.toList

  def cacheBlockTxs = for {
    height <- startBlock until bitcoin.getBlockCount - step by step
    txCacheFileAtHeight = new java.io.File(me fileName height)
    if !txCacheFileAtHeight.exists

    _ = log(s"Caching ${txCacheFileAtHeight.getName}")
    rangeListJson = getRangeList(height).toJson.toString
  } Files.write(rangeListJson, txCacheFileAtHeight, Charsets.UTF_8)

  // QUERYING

  def getTxListByBlock(height: Int) = txCache get height match {
    case None => load(height - height % step) getOrElse fetch(height)
    case Some(txList) => txList
  }

  private def load(height: Int): Try[TxList] = Try {
    val rangeListJson = fromFile(me fileName height).mkString
    txCache ++= to[BlockTxCache](rangeListJson)
    txCache(height)
  }

  private def fetch(height: Int): TxList = {
    val cache = bitcoin.getBlock(height).tx.asScala.toList
    txCache(height) = cache
    cache
  }
}
