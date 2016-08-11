package com.btcontract.lncloud

import com.btcontract.lncloud.database.Database
import org.bitcoinj.core.BloomFilter


class Wraps(db: Database) extends Cleanable {
  var stack = db.getAllWraps.sortBy(wrap => -wrap.stamp)
  def clean(stamp: Long) = synchronized(stack = for (wrap <- stack if wrap.stamp > stamp) yield wrap)
  def get(bloom: BloomFilter, cut: Long) = stack.takeWhile(_.stamp > cut).filter(bloom contains _.data.pubKey)
  def putWrap(wrap: Wrap) = synchronized(stack = wrap :: stack)
}