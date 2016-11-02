package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey
import scala.util.Try


class TxSigChecker {
  val ecKeys = values.pubKeys map HEX.decode map ECKey.fromPublicOnly
  val keysMap = values.pubKeys.map(_ take 16).zip(ecKeys).toMap

  def check(msg: String, sig: String, prefix: String) = {
    def verify(key: ECKey) = Try apply key.verifyMessage(msg, sig)
    keysMap.get(prefix).exists(key => verify(key).isSuccess)
  }
}
