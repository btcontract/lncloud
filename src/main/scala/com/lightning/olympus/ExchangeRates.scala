package com.lightning.olympus

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.olympus.JsonHttpUtils._
import org.knowm.xchange.currency.CurrencyPair._
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Tools.none
import scala.util.Try

import org.knowm.xchange.ExchangeFactory
import org.knowm.xchange.currency.CurrencyPair
import org.knowm.xchange.bitcoinaverage.BitcoinAverageExchange
import org.knowm.xchange.mercadobitcoin.MercadoBitcoinExchange
import org.knowm.xchange.coinbasepro.CoinbaseProExchange
import org.knowm.xchange.btcmarkets.BTCMarketsExchange
import org.knowm.xchange.bitfinex.v1.BitfinexExchange
import org.knowm.xchange.bitstamp.BitstampExchange
import org.knowm.xchange.coinmate.CoinmateExchange
import org.knowm.xchange.paymium.PaymiumExchange
import org.knowm.xchange.lakebtc.LakeBTCExchange
import org.knowm.xchange.kraken.KrakenExchange
import org.knowm.xchange.quoine.QuoineExchange
import org.knowm.xchange.exmo.ExmoExchange


class AveragePrice(val pair: CurrencyPair, val exchanges: List[String], val code: String) {
  val history = new ConcurrentHashMap[String, PriceHistory].asScala
  type PriceTry = Try[BigDecimal]

  def update: Unit =
    for (exchangeName <- exchanges)
      history.getOrElseUpdate(exchangeName, new PriceHistory) add Try {
        val exchangeInstance = ExchangeFactory.INSTANCE createExchange exchangeName
        exchangeInstance.getMarketDataService.getTicker(pair).getLast: BigDecimal
      }

  def average = Try {
    val recentPrices = history.values.flatMap(_.recentValue)
    recentPrices.map(_.get).sum / recentPrices.size
  } getOrElse BigDecimal(0)

  class PriceHistory {
    var prices = List.empty[PriceTry]
    def recentValue = prices.find(_.isSuccess)
    def add(item: PriceTry) = prices = item :: prices take 5
  }
}

class ExchangeRates {
  val usd = new AveragePrice(BTC_USD, classOf[BitstampExchange].getName :: classOf[BitfinexExchange].getName :: classOf[KrakenExchange].getName :: classOf[CoinbaseProExchange].getName :: Nil, "usd")
  val eur = new AveragePrice(BTC_EUR, classOf[PaymiumExchange].getName :: classOf[KrakenExchange].getName :: classOf[BitstampExchange].getName :: classOf[CoinbaseProExchange].getName :: Nil, "eur")
  val gbp = new AveragePrice(BTC_GBP, classOf[PaymiumExchange].getName :: classOf[CoinbaseProExchange].getName :: classOf[BitfinexExchange].getName :: classOf[KrakenExchange].getName :: Nil, "gbp")
  val jpy = new AveragePrice(BTC_JPY, classOf[KrakenExchange].getName :: classOf[BitcoinAverageExchange].getName :: classOf[QuoineExchange].getName :: Nil, "jpy")
  val brl = new AveragePrice(BTC_BRL, classOf[BitcoinAverageExchange].getName :: classOf[MercadoBitcoinExchange].getName :: Nil, "brl")
  val aud = new AveragePrice(BTC_AUD, classOf[BitcoinAverageExchange].getName :: classOf[BTCMarketsExchange].getName :: Nil, "aud")
  val czk = new AveragePrice(BTC_CZK, classOf[BitcoinAverageExchange].getName :: classOf[CoinmateExchange].getName :: Nil, "czk")
  val rub = new AveragePrice(BTC_RUB, classOf[BitcoinAverageExchange].getName :: classOf[ExmoExchange].getName :: Nil, "rub")
  val cad = new AveragePrice(BTC_CAD, classOf[KrakenExchange].getName :: classOf[LakeBTCExchange].getName :: Nil, "cad")
  val cny = new AveragePrice(BTC_CNY, classOf[BitcoinAverageExchange].getName :: Nil, "cny")
  val inr = new AveragePrice(BTC_INR, classOf[BitcoinAverageExchange].getName :: Nil, "inr")
  val ils = new AveragePrice(BTC_ILS, classOf[BitcoinAverageExchange].getName :: Nil, "ils")

  def displayState = for {
    average: AveragePrice <- currencies
    exchange \ history <- average.history
    humanHistory = history.prices mkString "\r\n-- "
  } yield s"${average.pair} $exchange \r\n-- $humanHistory"

  val currencies = List(usd, eur, jpy, gbp, cny, inr, ils, cad, rub, brl, aud, czk)
  def update(some: Any) = for (averagePairPrice <- currencies) averagePairPrice.update
  retry(obsOnIO map update, pickInc, 4 to 6).repeatWhen(_ delay 30.minutes).subscribe(none)
}
