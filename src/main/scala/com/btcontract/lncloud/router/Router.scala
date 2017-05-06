package com.btcontract.lncloud.router

import com.btcontract.lncloud._
import collection.JavaConverters._
import com.lightning.wallet.ln.wire._

import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListSet}
import com.lightning.wallet.ln.{Announcements, Features, Tools}
import fr.acinq.bitcoin.{BinaryData, Script, Transaction}
import rx.lang.scala.{Observable => Obs}

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import com.lightning.wallet.ln.wire.LightningMessageCodecs.PaymentRoute
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.lightning.wallet.ln.Scripts.multiSig2of2
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import com.btcontract.lncloud.Utils.errLog
import com.lightning.wallet.ln.Tools.none
import fr.acinq.bitcoin.Crypto.PublicKey
import language.implicitConversions
import scala.collection.mutable


object Router { me =>
  private val stash = new ConcurrentSkipListSet[RoutingMessage].asScala
  private val awaits = new ConcurrentSkipListSet[ChannelAnnouncement].asScala
  private val updates = new ConcurrentHashMap[ChanDirection, ChannelUpdate].asScala
  val black: mutable.Set[BinaryData] = new ConcurrentSkipListSet[BinaryData].asScala
  implicit def binData2PublicKey(data: BinaryData): PublicKey = PublicKey(data)

  private val expiration = 86400 * 1000 * 7 * 4
  val channels = new ChannelsWrapper
  var finder = new GraphWrapper
  val nodes = new NodesWrapper

  class GraphWrapper {
    private lazy val graph = {
      val chanDirectionClass = classOf[ChanDirection]
      val defaultDirectedGraph = new DefaultDirectedGraph
        [BinaryData, ChanDirection](chanDirectionClass)

      for (direction <- updates.keys) {
        Seq(direction.from, direction.to) foreach defaultDirectedGraph.addVertex
        defaultDirectedGraph.addEdge(direction.from, direction.to, direction)
      }

      new CachedAllDirectedPaths[BinaryData,
        ChanDirection](defaultDirectedGraph)
    }

    // Allow maximum of 7 hops for now
    def findRoutes(from: BinaryData, to: BinaryData): Seq[PaymentRoute] =
      for (foundPath <- graph.getAllPaths(from, to, true, 7).asScala) yield
        for (dir <- foundPath.getEdgeList.asScala.toVector) yield
          Hop(dir.from, dir.to, me updates dir)
  }

  class ChannelsWrapper {
    type ShortChannelIds = Set[Long]
    val nodeId2Chans: mutable.Map[BinaryData, ShortChannelIds] =
      new ConcurrentHashMap[BinaryData, ShortChannelIds]
        .asScala.withDefaultValue(Set.empty)

    val chanId2Info: mutable.Map[Long, ChanInfo] =
      new ConcurrentHashMap[Long, ChanInfo].asScala

    val txId2Info: mutable.Map[BinaryData, ChanInfo] =
      new ConcurrentHashMap[BinaryData, ChanInfo].asScala

    def rm(info: ChanInfo): Unit = {
      val node1Chans = nodeId2Chans(info.ca.nodeId1) - info.ca.shortChannelId
      val node2Chans = nodeId2Chans(info.ca.nodeId2) - info.ca.shortChannelId
      if (node1Chans.isEmpty) nodeId2Chans remove info.ca.nodeId1 else nodeId2Chans(info.ca.nodeId1) = node1Chans
      if (node2Chans.isEmpty) nodeId2Chans remove info.ca.nodeId2 else nodeId2Chans(info.ca.nodeId2) = node2Chans
      chanId2Info remove info.ca.shortChannelId
      txId2Info remove info.txid
    }

    def add(info: ChanInfo): Unit = {
      // Record multiple mappings for various queries
      nodeId2Chans(info.ca.nodeId1) += info.ca.shortChannelId
      nodeId2Chans(info.ca.nodeId2) += info.ca.shortChannelId
      chanId2Info(info.ca.shortChannelId) = info
      txId2Info(info.txid) = info
    }

    // Same channel with valid sigs but different node ids
    def isBad(info1: ChanInfo): Option[ChanInfo] = chanId2Info.get(info1.ca.shortChannelId)
      .find(info => info.ca.nodeId1 != info1.ca.nodeId1 || info.ca.nodeId2 != info.ca.nodeId2)
  }

  class NodesWrapper {
    val searchTree: ConcurrentRadixTree[NodeAnnouncement] =
      new ConcurrentRadixTree[NodeAnnouncement](new DefaultCharArrayNodeFactory)

    val id2Node: mutable.Map[BinaryData, NodeAnnouncement] =
      new ConcurrentHashMap[BinaryData, NodeAnnouncement].asScala

    def rm(node: NodeAnnouncement): Unit =
      id2Node get node.nodeId foreach { found =>
        searchTree remove found.identifier
        id2Node remove found.nodeId
      }

    def add(newAnnouncement: NodeAnnouncement): Unit = {
      searchTree.put(newAnnouncement.identifier, newAnnouncement)
      id2Node(newAnnouncement.nodeId) = newAnnouncement
    }
  }

  private def resend =
    if (awaits.isEmpty)
      for (message <- stash) {
        stash.remove(elem = message)
        receive(elem = message)
      }

  def receive(elem: RoutingMessage): Unit = elem match {
    case ca: ChannelAnnouncement if black.contains(ca.nodeId1) || black.contains(ca.nodeId2) => Tools log s"Ignoring $ca"
    case ca: ChannelAnnouncement if !Announcements.checkSigs(ca) => Tools log s"Ignoring invalid signatures $ca"
    case ca: ChannelAnnouncement =>

      awaits add ca
      val observable = Blockchain.getInfo(ca) map addChannel
      observable.subscribe(none, e => Tools log s"No utxo for $ca, $e")
      observable.subscribe(_ => awaits remove ca, _ => awaits remove ca)
      observable.subscribe(_ => resend, _ => resend)

    case node: NodeAnnouncement if awaits.nonEmpty => stash add node
    case node: NodeAnnouncement if black.contains(node.nodeId) => Tools log s"Ignoring $node"
    case node: NodeAnnouncement if channels.nodeId2Chans(node.nodeId).isEmpty => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if nodes.id2Node.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Ignoring outdated $node"
    case node: NodeAnnouncement if !Announcements.checkSig(node) => Tools log s"Ignoring invalid signatures $node"
    case node: NodeAnnouncement if Features.isSet(node.features, Features.CHANNELS_PUBLIC_BIT) => nodes rm node

    case node: NodeAnnouncement =>
      // Might be a new one or an update
      // with a new alias so should replace

      nodes rm node
      nodes add node

    case cu: ChannelUpdate if awaits.nonEmpty => stash add cu
    case cu: ChannelUpdate if cu.flags.data.size != 2 => Tools log s"Ignoring invalid flags length ${cu.flags.data.size}"
    case cu: ChannelUpdate if !channels.chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"

    case cu: ChannelUpdate => try {
      val info = channels chanId2Info cu.shortChannelId
      val isNode1 = Announcements isNode1 cu.flags

      val channelDirection =
        if (isNode1) ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        else ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)

      require(!black.contains(info.ca.nodeId1) & !black.contains(info.ca.nodeId2), s"Ignoring $cu")
      require(!updates.get(channelDirection).exists(_.timestamp >= cu.timestamp), s"Ignoring outdated $cu")
      require(Announcements.checkSig(cu, channelDirection.from), s"Ignoring invalid signatures for $cu")
      updates(channelDirection) = cu
    } catch errLog
  }

  private def addChannel(info: ChanInfo): Unit = {
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.txo.hex), s"Incorrect script in $info")

    channels isBad info match {
      case None => channels add info

      case Some(old: ChanInfo) =>
        val compromisedNodeIds = List(old.ca.nodeId1, old.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
        complexRemove(compromisedNodeIds.flatMap(channels.nodeId2Chans).map(channels.chanId2Info):_*)
        Tools log s"Compromised $info since $old exists"
        compromisedNodeIds foreach black.add
    }
  }

  private def checkOutdated = System.currentTimeMillis match { case now =>
    val dirsToRemove = updates collect { case (dir, cu) if cu.lastSeen < now - expiration => dir }
    complexRemove(dirsToRemove.map(updates).map(channels chanId2Info _.shortChannelId).toSeq:_*)
  }

  private def complexRemove(infos: ChanInfo*) = {
    for (channelInfo <- infos) channels rm channelInfo
    // Removing channels obviously affects updates so once channels are done we also remove related updates
    // Removing channels may also result in new lost nodes so all nodes with zero channels are removed
    updates.keys.filterNot(channels.chanId2Info contains _.channelId).foreach(updates.remove)
    nodes.id2Node.filterKeys(channels.nodeId2Chans(_).isEmpty).values.foreach(nodes.rm)
    Tools log s"Removed channels: $infos"
    finder = new GraphWrapper
  }

  private val spentChannelsWatcher = new BlockchainListener {
    override def onNewTx(tx: Transaction): Unit = for { input <- tx.txIn
      info <- channels.txId2Info.get(key = input.outPoint.txid)
      if info.ca.outputIndex == input.outPoint.index
    } complexRemove(info)

    override def onNewBlock(block: Block): Unit = {
      val spent = channels.txId2Info.values.filter(Blockchain.isSpent)
      if (spent.isEmpty) Tools log s"No spent channels at ${block.height}"
      else complexRemove(spent.toSeq:_*)
    }
  }

  Obs.interval(6.hours).foreach(_ => checkOutdated, errLog)
  Blockchain addListener spentChannelsWatcher
}