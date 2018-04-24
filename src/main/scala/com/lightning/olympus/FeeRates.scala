package com.lightning.olympus

import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._
import com.lightning.olympus.JsonHttpUtils._
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Tools.none
import scala.collection.mutable
import scala.util.Try


class FeeRates {
  type TryDouble = Try[Double]
  val rates: mutable.Map[Int, TryDouble] = new ConcurrentHashMap[Int, TryDouble].asScala
  def update(some: Any) = for (bs <- 2 to 12) rates(bs) = Try(bitcoin getEstimateSmartFee bs)
  retry(obsOnIO map update, pickInc, 4 to 6).repeatWhen(_ delay 15.minutes).subscribe(none)
}