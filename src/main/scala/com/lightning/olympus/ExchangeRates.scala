package com.lightning.olympus

import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.olympus.JsonHttpUtils._
import org.knowm.xchange.currency.CurrencyPair._
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import com.lightning.walletapp.ln.Tools.none
import org.knowm.xchange.ExchangeFactory
import scala.util.Try

import org.knowm.xchange.bitcoinaverage.BitcoinAverageExchange
import org.knowm.xchange.bitfinex.v1.BitfinexExchange
import org.knowm.xchange.bitstamp.BitstampExchange
import org.knowm.xchange.paymium.PaymiumExchange
import org.knowm.xchange.kraken.KrakenExchange
import org.knowm.xchange.currency.CurrencyPair
import org.knowm.xchange.quoine.QuoineExchange
import org.knowm.xchange.gdax.GDAXExchange


class AveragePrice(val pair: CurrencyPair, val code: String) {
  val history = new ConcurrentHashMap[String, PriceHistory].asScala
  val exchanges: List[String] = List.empty
  type PriceTry = Try[BigDecimal]

  def update: Unit = for (exchangeName <- exchanges)
    history.getOrElseUpdate(exchangeName, new PriceHistory) add Try {
      val exchangeInstance = ExchangeFactory.INSTANCE createExchange exchangeName
      exchangeInstance.getMarketDataService.getTicker(pair).getLast: BigDecimal
    }

  def average = Try {
    val recentPrices = history.values.flatMap(_.recentValue)
    recentPrices.map(_.get).sum / recentPrices.size
  } getOrElse BigDecimal(0)

  class PriceHistory {
    var prices: List[PriceTry] = Nil
    def recentValue = prices.find(_.isSuccess)
    def add(item: PriceTry) = prices = item :: prices take 5
  }
}

class ExchangeRates {
  val usd = new AveragePrice(BTC_USD, "dollar") {
    override val exchanges = classOf[BitstampExchange].getName ::
      classOf[BitfinexExchange].getName :: classOf[KrakenExchange].getName ::
      classOf[BitcoinAverageExchange].getName :: classOf[GDAXExchange].getName :: Nil
  }

  val eur = new AveragePrice(BTC_EUR, "euro") {
    override val exchanges = classOf[PaymiumExchange].getName ::
      classOf[KrakenExchange].getName :: classOf[BitstampExchange].getName ::
      classOf[BitcoinAverageExchange].getName :: classOf[GDAXExchange].getName :: Nil
  }

  val cny = new AveragePrice(BTC_CNY, "yuan") {
    override val exchanges = classOf[BitcoinAverageExchange].getName :: Nil
  }

  val jpy = new AveragePrice(BTC_JPY, "yen") {
    override val exchanges = classOf[KrakenExchange].getName ::
      classOf[BitcoinAverageExchange].getName ::
      classOf[QuoineExchange].getName :: Nil
  }

  def displayState = for {
    average: AveragePrice <- currencies
    exchange \ history <- average.history
    humanHistory = history.prices mkString "\r\n-- "
  } yield s"${average.pair} $exchange \r\n-- $humanHistory"

  val currencies = List(usd, eur, jpy, cny)
  def update(some: Any) = for (average <- currencies) average.update
  retry(obsOnIO map update, pickInc, 4 to 6).repeatWhen(_ delay 30.minutes)
    .doOnNext(_ => Tools log "Exchange rates were updated").subscribe(none)
}
