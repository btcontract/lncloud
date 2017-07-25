package com.btcontract.lncloud

import com.lightning.wallet.ln._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._

import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, Script}

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import com.lightning.wallet.ln.wire.LightningMessageCodecs.PaymentRoute
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.lightning.wallet.ln.Scripts.multiSig2of2
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import com.btcontract.lncloud.Utils.errLog
import scala.language.implicitConversions
import com.lightning.wallet.ln.Tools.wrap
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import scala.util.Try


object Router { me =>
  // Blacklisted node ids
  val black = mutable.Set.empty[PublicKey]
  // A cached graph to search for routes, updated on new and deleted updates
  var finder = new GraphFinder(mutable.Map.empty[ChanDirection, ChannelUpdate], 7)
  val maps = new Mappings

  class GraphFinder(val updates: mutable.Map[ChanDirection, ChannelUpdate], val maxPathLength: Int) {
    def outdatedChannels: Iterable[ChannelUpdate] = updates.values.filter(_.lastSeen < System.currentTimeMillis - 86400 * 1000 * 7)
    def augmented(dir: ChanDirection, upd: ChannelUpdate): GraphFinder = new GraphFinder(updates.updated(dir, upd), maxPathLength)
    def findRoutes(from: PublicKey, to: PublicKey): Seq[PaymentRoute] = Try apply findPaths(from, to) getOrElse Nil
    private lazy val directedGraph = new DefaultDirectedGraph[PublicKey, ChanDirection](chanDirectionClass)
    private lazy val chanDirectionClass = classOf[ChanDirection]

    private lazy val graph = updates.keys match { case keys =>
      for (direction <- keys) Seq(direction.from, direction.to) foreach directedGraph.addVertex
      for (direction <- keys) directedGraph.addEdge(direction.from, direction.to, direction)
      new CachedAllDirectedPaths[PublicKey, ChanDirection](directedGraph)
    }

    private def findPaths(from: PublicKey, to: PublicKey): Seq[PaymentRoute] =
      for (foundPath <- graph.getAllPaths(from, to, true, maxPathLength).asScala) yield
        for (dir <- foundPath.getEdgeList.asScala.toVector) yield
          Hop(dir.from, dir.to, updates apply dir)
  }

  class Mappings {
    type ShortChannelIds = Set[Long]
    val chanId2Info = mutable.Map.empty[Long, ChanInfo]
    val txId2Info = mutable.Map.empty[BinaryData, ChanInfo]
    val nodeId2Announce = mutable.Map.empty[PublicKey, NodeAnnouncement]
    val nodeId2Chans = mutable.Map.empty[PublicKey, ShortChannelIds] withDefaultValue Set.empty
    val searchTrie = new ConcurrentRadixTree[NodeAnnouncement](new DefaultCharArrayNodeFactory)

    def rmChanInfo(info: ChanInfo): Unit = {
      nodeId2Chans(info.ca.nodeId1) -= info.ca.shortChannelId
      nodeId2Chans(info.ca.nodeId2) -= info.ca.shortChannelId
      chanId2Info -= info.ca.shortChannelId
      txId2Info -= info.txid
    }

    def addChanInfo(info: ChanInfo): Unit = {
      // Record multiple mappings for various queries
      nodeId2Chans(info.ca.nodeId1) += info.ca.shortChannelId
      nodeId2Chans(info.ca.nodeId2) += info.ca.shortChannelId
      chanId2Info(info.ca.shortChannelId) = info
      txId2Info(info.txid) = info
    }

    def rmNode(node: NodeAnnouncement) =
      nodeId2Announce get node.nodeId foreach { old =>
        // Announce may have a new alias so we search for
        // an old one because nodeId should remain the same
        searchTrie remove old.nodeId.toString
        searchTrie remove old.identifier
        nodeId2Announce -= old.nodeId
      }

    def addNode(node: NodeAnnouncement) = {
      searchTrie.put(node.nodeId.toString, node)
      searchTrie.put(node.identifier, node)
      nodeId2Announce(node.nodeId) = node
    }

    // Same channel with valid sigs but different node ids
    def isBadChannel(info1: ChanInfo): Option[ChanInfo] = chanId2Info.get(info1.ca.shortChannelId)
      .find(info => info.ca.nodeId1 != info1.ca.nodeId1 || info.ca.nodeId2 != info.ca.nodeId2)
  }

  def receive(msg: LightningMessage) = me synchronized doReceive(msg)
  private def doReceive(message: LightningMessage): Unit = message match {
    case ca: ChannelAnnouncement if black.contains(ca.nodeId1) || black.contains(ca.nodeId2) => Tools log s"Blacklisted $ca"
    case ca: ChannelAnnouncement if !Announcements.checkSigs(ca) => Tools log s"Ignoring invalid signatures $ca"
    case ca: ChannelAnnouncement => Blockchain getInfo ca map updateOrBlacklistChannel

    case node: NodeAnnouncement if black.contains(node.nodeId) => Tools log s"Ignoring $node"
    case node: NodeAnnouncement if maps.nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !maps.nodeId2Chans.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if !Announcements.checkSig(node) => Tools log s"Ignoring invalid signatures $node"
    case node: NodeAnnouncement => wrap(maps addNode node)(maps rmNode node) // Might be an update

    case cu: ChannelUpdate if cu.flags.data.size != 2 => Tools log s"Ignoring invalid flags length ${cu.flags.data.size}"
    case cu: ChannelUpdate if !maps.chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"

    case cu: ChannelUpdate => try {
      val info = maps chanId2Info cu.shortChannelId
      val chanDirection = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      require(!black.contains(info.ca.nodeId1) & !black.contains(info.ca.nodeId2), s"Ignoring $cu")
      require(finder.updates.get(chanDirection).forall(_.timestamp < cu.timestamp), s"Outdated $cu")
      require(Announcements.checkSig(cu, chanDirection.from), s"Ignoring invalid signatures for $cu")
      if (finder.updates contains chanDirection) finder.updates(chanDirection) = cu
      else finder = finder.augmented(chanDirection, cu)
    } catch errLog

    case otherwise =>
      Tools log s"Unhandled $otherwise"
  }

  private def updateOrBlacklistChannel(info: ChanInfo): Unit = {
    // May fail because scripts don't match, may be blacklisted or added/updated
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.key.hex), s"Incorrect script in $info")

    maps isBadChannel info map { old: ChanInfo =>
      val compromisedNodes = List(old.ca.nodeId1, old.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
      complexRemove(compromisedNodes.flatMap(maps.nodeId2Chans).map(maps.chanId2Info):_*)
      Tools log s"Compromised $info because $old exists already"
      for (node <- compromisedNodes) yield black add node
    } getOrElse maps.addChanInfo(info)
  }

  def complexRemove(infos: ChanInfo*) = me synchronized {
    for (chanInfoToRemove <- infos) maps rmChanInfo chanInfoToRemove
    // Once channel infos are removed we also have to remove all the affected updates
    // Removal also may result in lost nodes so all nodes with now zero channels are removed too
    maps.nodeId2Announce.filterKeys(nodeId => maps.nodeId2Chans(nodeId).isEmpty).values foreach maps.rmNode
    finder = new GraphFinder(finder.updates.filter(maps.chanId2Info contains _._1.channelId), finder.maxPathLength)
    Tools log s"Removed the following channels: $infos"
  }

  // Channels may disappear silently without closing transaction on a blockchain so we must remove them too
  private def outdatedInfos: Iterable[ChanInfo] = finder.outdatedChannels.map(maps chanId2Info _.shortChannelId)
  Obs.interval(2.hours).foreach(_ => complexRemove(outdatedInfos.toSeq:_*), errLog)
}