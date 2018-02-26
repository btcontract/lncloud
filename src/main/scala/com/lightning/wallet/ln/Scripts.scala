package com.lightning.wallet.ln

import fr.acinq.bitcoin._


object Scripts { me =>
  def cltvBlocks(tx: Transaction): Long =
    if (tx.lockTime <= LockTimeThreshold) tx.lockTime else 0
}