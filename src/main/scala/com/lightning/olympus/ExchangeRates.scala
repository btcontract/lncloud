package com.lightning.olympus

import com.lightning.walletapp.ln._
import com.lightning.olympus.JsonHttpUtils._
import com.lightning.olympus.ExchangeRates._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._
import com.github.kevinsawicki.http.HttpRequest.get
import com.lightning.walletapp.ln.Tools.random
import scala.concurrent.duration.DurationInt


object ExchangeRates {
  type BitpayItemList = List[BitpayItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
}

case class BitpayItem(code: String, rate: Double)
case class CoinGeckoItem(unit: String, value: Double)
case class Bitpay(data: BitpayItemList) { val res = for (BitpayItem(code, rate) <- data) yield code.toLowerCase -> rate }
case class CoinGecko(rates: CoinGeckoItemMap) { val res = for (code \ CoinGeckoItem(_, value) <- rates) yield code -> value }

class ExchangeRates {
  var cache = Map.empty[String, Double]
  var updated = System.currentTimeMillis

  def reloadData = try {
    random nextInt 2 match {
      case 0 => to[Bitpay](get("https://bitpay.com/rates").trustAllCerts.trustAllHosts.body).res
      case 1 => to[CoinGecko](get("https://api.coingecko.com/api/v3/exchange_rates").trustAllCerts.trustAllHosts.body).res
    }
  } catch {
    case error: Throwable =>
      val info = error.getMessage
      Tools.log(s"ExchangeRates: $info")
      // Rethrow to let retry do its job
      throw error
  }

  // In case of failure repeatedly try to fetch rates with increasing delays between tries
  val fetch = retry(obsOnIO.map(_ => reloadData), pickInc, 0 to 10000 by 20).repeatWhen(_ delay 60.minutes)

  fetch.foreach { freshFiatRates =>
    updated = System.currentTimeMillis
    cache = freshFiatRates.toMap
  }
}
