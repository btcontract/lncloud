package com.btcontract.lncloud

import rx.lang.scala.{Observable => Obs}
import java.net.{InetAddress, InetSocketAddress}
import com.btcontract.lncloud.Utils.{bitcoin, values}
import com.lightning.wallet.ln.{ConnectionListener, ConnectionManager, Tools}
import com.lightning.wallet.ln.wire.{Init, LightningMessage, NodeAnnouncement}

import collection.JavaConverters._
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.btcontract.lncloud.database.Database
import scala.concurrent.duration.DurationInt
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Transaction
import scala.util.Try


class ListenerManager(db: Database) {
  def connect = ConnectionManager requestConnection announce
  val announce = NodeAnnouncement(null, null, 0, values.eclairNodeId, null, "Routing source",
    new InetSocketAddress(InetAddress getByName values.eclairIp, values.eclairPort) :: Nil)

  ConnectionManager.listeners += new ConnectionListener {
    override def onMessage(msg: LightningMessage) = Router receive msg
    override def onOperational(id: PublicKey, their: Init) = Tools log "Socket is operational"
    override def onTerminalError(id: PublicKey) = ConnectionManager.connections.get(id).foreach(_.socket.close)
    override def onDisconnect(id: PublicKey): Unit = Obs.just(Tools log "Restarting socket").delay(10.seconds)
      .subscribe(_ => connect, _.printStackTrace)
  }

  Blockchain.listeners += new BlockchainListener {
    override def onNewTx(transaction: Transaction) = for {
      // We need to check if any input spends a channel output
      // Respected payment channels should be removed

      input <- transaction.txIn
      chanInfo <- Router.maps.txId2Info get input.outPoint.txid
      if chanInfo.ca.outputIndex == input.outPoint.index
    } Router.complexRemove(chanInfo)

    override def onNewBlock(block: Block) = {
      val spent = Router.maps.txId2Info.values filter Blockchain.isSpent
      if (spent.isEmpty) Tools log s"No spent channels at ${block.height}"
      else Router.complexRemove(spent.toSeq:_*)
    }
  }

  Blockchain.listeners +=
    new BlockchainListener {
      override def onNewBlock(block: Block) = for {
        // We need to save txids of parent transactions
        // Lite clients will need this to extract preimages

        txid <- block.tx.asScala
        tx <- Try(bitcoin getRawTransactionHex txid) map Transaction.read
      } db.putTx(tx.txIn.map(_.outPoint.txid.toString), tx.toString)
    }
}
