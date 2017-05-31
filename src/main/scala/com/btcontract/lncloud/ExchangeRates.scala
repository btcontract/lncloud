package com.btcontract.lncloud

import collection.JavaConverters._
import com.btcontract.lncloud.JsonHttpUtils._
import org.knowm.xchange.currency.CurrencyPair._

import com.lightning.wallet.ln.~
import org.knowm.xchange.currency.CurrencyPair
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.ln.Tools.none
import com.lightning.wallet.ln.Tools
import scala.collection.mutable
import scala.util.Try

import org.knowm.xchange.ExchangeFactory
import org.knowm.xchange.bitfinex.v1.BitfinexExchange
import org.knowm.xchange.bitstamp.BitstampExchange
import org.knowm.xchange.btcchina.BTCChinaExchange
import org.knowm.xchange.paymium.PaymiumExchange
import org.knowm.xchange.kraken.KrakenExchange
import org.knowm.xchange.okcoin.OkCoinExchange
import org.knowm.xchange.btc38.Btc38Exchange
import org.knowm.xchange.chbtc.ChbtcExchange
import org.knowm.xchange.bter.BTERExchange
import org.knowm.xchange.gdax.GDAXExchange


class AveragePrice(val pair: CurrencyPair) {
  def code: String = pair.counter.getCurrencyCode
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

  type PriceTry = Try[BigDecimal]
  val exchanges: List[String] = List.empty
  val history: mutable.Map[String, PriceHistory] =
    new ConcurrentHashMap[String, PriceHistory].asScala
}

class ExchangeRates {
  val usd = new AveragePrice(BTC_USD) {
    override val exchanges: List[String] =
      classOf[BitstampExchange].getName ::
        classOf[BitfinexExchange].getName ::
        classOf[KrakenExchange].getName ::
        classOf[GDAXExchange].getName ::
        Nil
  }

  val eur = new AveragePrice(BTC_EUR) {
    override val exchanges: List[String] =
      classOf[PaymiumExchange].getName ::
        classOf[KrakenExchange].getName ::
        classOf[BitstampExchange].getName ::
        classOf[GDAXExchange].getName ::
        Nil
  }

  val cny = new AveragePrice(BTC_CNY) {
    override val exchanges: List[String] =
      classOf[BTCChinaExchange].getName ::
        classOf[OkCoinExchange].getName ::
        classOf[ChbtcExchange].getName ::
        classOf[Btc38Exchange].getName ::
        classOf[BTERExchange].getName ::
        Nil
  }

  def displayState = for {
    averagePrice: AveragePrice <- currencies
    exchange ~ history <- averagePrice.history
  } yield {
    val humanHistory = history.prices mkString "\r\n-- "
    s"${averagePrice.pair} $exchange \r\n-- $humanHistory"
  }

  val currencies = List(usd, eur, cny)
  retry(obsOn(currencies.foreach(_.update), IOScheduler.apply), pickInc, 1 to 3)
    .repeatWhen(_ delay 30.minutes).doOnNext(_ => Tools log "Exchange rates were updated")
    .subscribe(none)
}
