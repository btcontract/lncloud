package com.lightning.walletapp.ln


object Scripts {
  import fr.acinq.bitcoin.{LockTimeThreshold, Transaction}
  def cltvBlocks(tx: Transaction): Long = if (tx.lockTime <= LockTimeThreshold) tx.lockTime else 0L
}