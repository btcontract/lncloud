package com.lightning.olympus

import com.lightning.wallet.ln._
import com.softwaremill.quicklens._
import com.lightning.olympus.Utils._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._
import com.googlecode.concurrenttrees.radix.node.concrete._
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.lightning.wallet.ln.Scripts.multiSig2of2
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import scala.util.Try

import com.lightning.wallet.ln.Tools.{wrap, random}
import fr.acinq.bitcoin.{BinaryData, Script}
import rx.lang.scala.{Observable => Obs}


object Router { me =>
  type NodeIdSet = Set[PublicKey]
  type ShortChannelIdSet = Set[Long]
  type NodeChannelsMap = mutable.Map[PublicKey, ShortChannelIdSet]
  type CachedPathGraph = CachedAllDirectedPaths[PublicKey, ChanDirection]
  var finder = GraphFinder(mutable.Map.empty[ChanDirection, ChannelUpdate], 6)
  val black = mutable.Set.empty[PublicKey]
  val maps = new Mappings

  case class Node2Channels(nodeMap: NodeChannelsMap) {
    lazy val seq = nodeMap.toSeq.map { case core @ (_, chanIds) =>
      // Relatively well connected nodes have a 0.1 chance to pop up
      val popOutChance = random.nextDouble < 0.1D && chanIds.size > 20
      if (popOutChance) (core, chanIds.size * 40) else (core, chanIds.size)
    }.sortWith(_._2 > _._2).map(_._1)

    def plusShortChannelId(info: ChanInfo) = {
      nodeMap(info.ca.nodeId1) += info.ca.shortChannelId
      nodeMap(info.ca.nodeId2) += info.ca.shortChannelId
      Node2Channels(nodeMap)
    }

    def minusShortChannelId(info: ChanInfo) = {
      nodeMap(info.ca.nodeId1) -= info.ca.shortChannelId
      nodeMap(info.ca.nodeId2) -= info.ca.shortChannelId
      val mapping1 = nodeMap.filter(_._2.nonEmpty)
      Node2Channels(mapping1)
    }
  }

  case class GraphFinder(updates: mutable.Map[ChanDirection, ChannelUpdate], maxPathLength: Int) {
    // This class is intended to be fully replaced each time an update is disabled, outdated or added
    private lazy val cached: CachedPathGraph = refined(Set.empty, Set.empty)
    private val chanDirectionClass = classOf[ChanDirection]

    def safeFindPaths(ns: NodeIdSet, cs: ShortChannelIdSet, from: PublicKey, to: PublicKey) = Try {
      // First we check if we can use a cached graph to improve performance, then we return the most economic paths
      val res = if (ns.isEmpty && cs.isEmpty) findPaths(cached, from, to) else findPaths(refined(ns, cs), from, to)
      res sortBy { hops: Vector[Hop] => hops.map(_.lastUpdate.score).sum } take 10
    } getOrElse Nil

    private def findPaths(graph: CachedPathGraph, from: PublicKey, to: PublicKey) =
      for (path <- graph.getAllPaths(from, to, true, maxPathLength).asScala) yield
        for (direction <- path.getEdgeList.asScala.toVector) yield
          Hop(direction.from, updates apply direction)

    private def refined(withoutNodes: NodeIdSet, withoutChannels: ShortChannelIdSet) = {
      val directedGraph = new DefaultDirectedGraph[PublicKey, ChanDirection](chanDirectionClass)

      def insert(direction: ChanDirection) = {
        directedGraph.addVertex(direction.from)
        directedGraph.addVertex(direction.to)
        directedGraph.addEdge(direction.from,
          direction.to, direction)
      }

      for {
        direction <- updates.keys
        if !withoutChannels.contains(direction.channelId)
        if !withoutNodes.contains(direction.from)
        if !withoutNodes.contains(direction.to)
      } insert(direction)

      // Paths without specified routes
      new CachedPathGraph(directedGraph)
    }
  }

  class Mappings {
    val chanId2Info = mutable.Map.empty[Long, ChanInfo]
    val txId2Info = mutable.Map.empty[BinaryData, ChanInfo]
    val nodeId2Announce = mutable.Map.empty[PublicKey, NodeAnnouncement]
    val searchTrie = new ConcurrentRadixTree[NodeAnnouncement](new DefaultCharArrayNodeFactory)
    var nodeId2Chans = Node2Channels(mutable.Map.empty withDefaultValue Set.empty)

    def rmChanInfo(info: ChanInfo) = {
      nodeId2Chans = nodeId2Chans minusShortChannelId info
      chanId2Info -= info.ca.shortChannelId
      txId2Info -= info.txid
    }

    def addChanInfo(info: ChanInfo) = {
      nodeId2Chans = nodeId2Chans plusShortChannelId info
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
    case node: NodeAnnouncement if node.addresses.isEmpty => Tools log s"Ignoring node without public addresses $node"
    case node: NodeAnnouncement if maps.nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !maps.nodeId2Chans.nodeMap.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if !Announcements.checkSig(node) => Tools log s"Ignoring invalid signatures $node"
    case node: NodeAnnouncement => wrap(maps addNode node)(maps rmNode node) // Might be an update

    case cu: ChannelUpdate if cu.flags.data.size != 2 => Tools log s"Ignoring invalid flags length ${cu.flags.data.size}"
    case cu: ChannelUpdate if !maps.chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"
    case cu: ChannelUpdate if isOutdated(cu) => Tools log s"Ignoring outdated update $cu"

    case cu: ChannelUpdate => try {
      val info = maps chanId2Info cu.shortChannelId
      val isEnabled = Announcements isEnabled cu.flags
      val chanDirection = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      require(!black.contains(info.ca.nodeId1) & !black.contains(info.ca.nodeId2), s"Ignoring $cu")
      require(finder.updates.get(chanDirection).forall(_.timestamp < cu.timestamp), s"Outdated $cu")
      require(Announcements.checkSig(cu, chanDirection.from), s"Ignoring invalid signatures for $cu")

      // Removing and adding an update should replace a finder
      if (!isEnabled) finder = finder.copy(updates = finder.updates - chanDirection)
      else if (finder.updates contains chanDirection) finder.updates(chanDirection) = cu
      else finder = finder.modify(_.updates) setTo finder.updates.updated(chanDirection, cu)
    } catch errLog

    case _ =>
  }

  type ChanInfos = Iterable[ChanInfo]
  def complexRemove(infos: ChanInfos) = me synchronized {
    for (chanInfoToRemove <- infos) maps rmChanInfo chanInfoToRemove
    // Once channel infos are removed we also have to remove all the affected updates
    // Removal also may result in lost nodes so all nodes with now zero channels are removed too
    maps.nodeId2Announce.filterKeys(maps.nodeId2Chans.nodeMap(_).isEmpty).values foreach maps.rmNode
    finder = GraphFinder(finder.updates.filter(maps.chanId2Info contains _._1.channelId), finder.maxPathLength)
    Tools log s"Removed channels: $infos"
  }

  private def updateOrBlacklistChannel(info: ChanInfo): Unit = {
    // May fail because scripts don't match, may be blacklisted or added/updated
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.key.hex), s"Incorrect script in $info")

    maps.isBadChannel(info) map { compromised =>
      val toRemove = List(compromised.ca.nodeId1, compromised.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
      complexRemove(toRemove.flatMap(maps.nodeId2Chans.nodeMap) map maps.chanId2Info)
      for (blackKey <- toRemove) yield black add blackKey
    } getOrElse maps.addChanInfo(info)
  }

  // Channels may disappear without a closing on-chain so we must disable them if that happens
  private def isOutdated(cu: ChannelUpdate) = cu.timestamp < System.currentTimeMillis / 1000 - 3600 * 24 * 5
  private def outdatedInfos = finder.updates.values.filter(isOutdated).map(_.shortChannelId).map(maps.chanId2Info)
  Obs.interval(12.hours).foreach(_ => complexRemove(outdatedInfos), errLog)
}