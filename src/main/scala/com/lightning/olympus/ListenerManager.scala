package com.lightning.olympus

import com.lightning.olympus.Utils._
import scala.collection.JavaConverters._

import com.lightning.wallet.ln.wire.{Init, LightningMessage, NodeAnnouncement}
import com.lightning.wallet.ln.{ConnectionListener, ConnectionManager, Tools}
import java.net.{InetAddress, InetSocketAddress}
import rx.lang.scala.{Observable => Obs}

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.lightning.olympus.database.Database
import scala.concurrent.duration.DurationInt
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.BinaryData
import scala.util.Try


class ListenerManager(db: Database) {
  def connect = ConnectionManager requestConnection announce
  val announce = NodeAnnouncement(null, null, 0, values.eclairNodeId, null, "Routing source",
    new InetSocketAddress(InetAddress getByName values.eclairIp, values.eclairPort) :: Nil)

  ConnectionManager.listeners += new ConnectionListener {
    override def onMessage(msg: LightningMessage) = Router receive msg
    override def onOperational(id: PublicKey, their: Init) = Tools log "Socket is operational"
    override def onTerminalError(id: PublicKey) = ConnectionManager.connections.get(id).foreach(_.socket.close)
    override def onDisconnect(id: PublicKey): Unit = Obs.just(Tools log "Restarting socket").delay(5.seconds)
      .subscribe(_ => connect, _.printStackTrace)
  }

  Blockchain.listeners += new BlockchainListener {
    override def onNewTx(twr: TransactionWithRaw) = for {
      // We need to check if any input spends a channel output
      // related payment channels should be removed

      input <- twr.tx.txIn
      chanInfo <- Router.maps.txId2Info get input.outPoint.txid
      if chanInfo.ca.outputIndex == input.outPoint.index
    } Router complexRemove Seq(chanInfo)

    override def onNewBlock(block: Block) = {
      val chanInfos = Router.maps.txId2Info.values
      val spent = chanInfos filter Blockchain.isSpent
      if (spent.nonEmpty) Router complexRemove spent
    }
  }

  Blockchain.listeners +=
    new BlockchainListener {
      override def onNewTx(twr: TransactionWithRaw) = {
        val inputs = twr.tx.txIn.map(_.outPoint.txid.toString)
        db.putTx(inputs, twr.raw.toString)
      }

      override def onNewBlock(block: Block) = for {
        // We need to save which txids this one spends from
        // Lite clients will need this to extract preimages

        txid <- block.tx.asScala
        hexTry = Try(bitcoin getRawTransactionHex txid)
        twr <- hexTry map BinaryData.apply map TransactionWithRaw
      } onNewTx(twr)
    }
}
