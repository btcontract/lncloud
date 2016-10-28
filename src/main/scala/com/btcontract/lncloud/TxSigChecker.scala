package com.btcontract.lncloud

import com.btcontract.lncloud.Utils._
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.ECKey


class TxSigChecker {
  val ecKeys = values.pubKeys map HEX.decode map ECKey.fromPublicOnly
  val keysMap = values.pubKeys.map(_ take 16).zip(ecKeys).toMap

  def check(data: Bytes, sig: Bytes, prefix: String) = {
    def verify(fullECKey: ECKey) = fullECKey.verify(data, sig)
    keysMap get prefix exists verify
  }
}
