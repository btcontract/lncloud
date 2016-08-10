package com.btcontract.lncloud


object Wraps extends Cleanable {
  import org.bitcoinj.core.BloomFilter
  def clean(stamp: Long) = synchronized(stack = for (wrap <- stack if wrap.stamp > stamp) yield wrap)
  def get(bloom: BloomFilter, cut: Long) = stack.takeWhile(_.stamp > cut).filter(bloom contains _.data.pubKey)
  def putWrap(wrap: Wrap) = synchronized(stack = wrap :: stack)
  var stack = List.empty[Wrap]
}