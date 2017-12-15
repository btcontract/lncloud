package com.lightning.olympus

import com.lightning.wallet.ln._
import scala.collection.JavaConverters._
import com.lightning.olympus.JsonHttpUtils._
import org.knowm.xchange.currency.CurrencyPair._

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools.none
import org.knowm.xchange.ExchangeFactory
import scala.util.Try

import org.knowm.xchange.bitcoinaverage.BitcoinAverageExchange
import org.knowm.xchange.bitfinex.v1.BitfinexExchange
import org.knowm.xchange.bitstamp.BitstampExchange
import org.knowm.xchange.paymium.PaymiumExchange
import org.knowm.xchange.kraken.KrakenExchange
import org.knowm.xchange.okcoin.OkCoinExchange
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

  def average: PriceTry = Try {
    val recentPrices = history.values.flatMap(_.recentValue)
    recentPrices.map(_.get).sum / recentPrices.size
  }

  class PriceHistory {
    var prices: List[PriceTry] = Nil
    def add(item: PriceTry): Unit = prices = item :: prices take 5
    def recentValue: Option[PriceTry] = prices.find(_.isSuccess)
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
    override val exchanges = classOf[OkCoinExchange].getName ::
      classOf[BitcoinAverageExchange].getName :: Nil
  }

  val jpy = new AveragePrice(BTC_JPY, "yen") {
    override val exchanges = classOf[BitfinexExchange].getName :: classOf[KrakenExchange].getName ::
      classOf[BitcoinAverageExchange].getName :: classOf[QuoineExchange].getName :: Nil
  }

  def displayState = for {
    average: AveragePrice <- currencies
    exchange \ history <- average.history
    humanHistory = history.prices mkString "\r\n-- "
  } yield s"${average.pair} $exchange \r\n-- $humanHistory"

  val currencies = List(usd, eur, jpy, cny)
  retry(obsOn(currencies.foreach(_.update), IOScheduler.apply), pickInc, 1 to 3)
    .repeatWhen(_ delay 30.minutes).doOnNext(_ => Tools log "Exchange rates updated")
    .subscribe(none)
}
