package com.btcontract.lncloud

import scala.collection.JavaConverters._
import com.btcontract.lncloud.JsonHttpUtils._

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import com.btcontract.lncloud.Utils.bitcoin
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools.none
import com.lightning.wallet.ln.Tools
import scala.collection.mutable
import scala.util.Try


class FeeRates {
  type TryDouble = Try[Double]
  val rates: mutable.Map[Int, TryDouble] = new ConcurrentHashMap[Int, TryDouble].asScala
  def update: Unit = for (block <- 2 to 18) rates(block) = Try(bitcoin getEstimateFee block)
  retry(obsOn(update, IOScheduler.apply), pickInc, 1 to 3).repeatWhen(_ delay 15.minutes)
    .doOnNext(_ => Tools log "Fees were updated").subscribe(none)
}