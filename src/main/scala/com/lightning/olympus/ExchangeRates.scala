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

import org.knowm.xchange.bitfinex.v1.BitfinexExchange
import org.knowm.xchange.bitstamp.BitstampExchange
import org.knowm.xchange.btcchina.BTCChinaExchange
import org.knowm.xchange.paymium.PaymiumExchange
import org.knowm.xchange.kraken.KrakenExchange
import org.knowm.xchange.okcoin.OkCoinExchange
import org.knowm.xchange.currency.CurrencyPair
import org.knowm.xchange.btc38.Btc38Exchange
import org.knowm.xchange.chbtc.ChbtcExchange
import org.knowm.xchange.bter.BTERExchange
import org.knowm.xchange.gdax.GDAXExchange


class AveragePrice(val pair: CurrencyPair, val code: String) {
  val history = new ConcurrentHashMap[String, PriceHistory].asScala
  val exchanges: List[String] = List.empty
  type PriceTry = Try[BigDecimal]

  def update: Unit = for (exchangeName <- exchanges)
    history.getOrElseUpdate(exchangeName, new PriceHistory) add Try {
      val exchangeInstance = ExchangeFactory.INSTANCE createExchange exchangeName
      exchangeInstance.getPollingMarketDataService.getTicker(pair).getLast: BigDecimal
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
    override val exchanges: List[String] = classOf[BitstampExchange].getName ::
      classOf[BitfinexExchange].getName :: classOf[KrakenExchange].getName ::
      classOf[GDAXExchange].getName :: Nil
  }

  val eur = new AveragePrice(BTC_EUR, "euro") {
    override val exchanges: List[String] = classOf[PaymiumExchange].getName ::
      classOf[KrakenExchange].getName :: classOf[BitstampExchange].getName ::
      classOf[GDAXExchange].getName :: Nil
  }

  val cny = new AveragePrice(BTC_CNY, "yuan") {
    override val exchanges: List[String] = classOf[BTCChinaExchange].getName ::
      classOf[OkCoinExchange].getName :: classOf[ChbtcExchange].getName ::
      classOf[Btc38Exchange].getName :: classOf[BTERExchange].getName ::
      Nil
  }

  def displayState = for {
    averagePrice: AveragePrice <- currencies
    exchange \ history <- averagePrice.history
  } yield {
    val humanHistory = history.prices mkString "\r\n-- "
    s"${averagePrice.pair} $exchange \r\n-- $humanHistory"
  }

  val currencies = List(usd, eur, cny)
  retry(obsOn(currencies.foreach(_.update), IOScheduler.apply), pickInc, 1 to 3)
    .repeatWhen(_ delay 30.minutes).doOnNext(_ => Tools log "Exchange rates updated")
    .subscribe(none)
}
