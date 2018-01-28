package com.lightning.olympus

abstract class Statistics[O] {
  type Collection = Traversable[O]
  def extract(item: O): Double

  def mean(items: Collection): Double =
    items.map(extract).sum / items.size

  def variance(items: Collection, mean: Double): Double = (0D /: items) {
    case (total: Double, item) => math.pow(extract(item) - mean, 2) + total
  } / items.size

  def notOutlier(mu: Double, sd: Double, stdDevs: Double)(item: O) =
    extract(item) >= mu - sd * stdDevs && extract(item) <= mu + sd * stdDevs
}
