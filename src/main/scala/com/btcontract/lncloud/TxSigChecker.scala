package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey


class TxSigChecker {
  val ecKeys: List[ECKey] = values.pubKeys map HEX.decode map ECKey.fromPublicOnly
  val keysMap: Map[String, ECKey] = values.pubKeys.map(_ take 16).zip(ecKeys).toMap
  def check(msg: Bytes, sig: Bytes, prefix: String): Option[Boolean] =
    for (key <- keysMap get prefix) yield key.verify(msg, sig)
}
