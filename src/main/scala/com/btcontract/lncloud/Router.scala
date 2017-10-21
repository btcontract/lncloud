package com.btcontract.lncloud

import com.lightning.wallet.ln._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._
import com.googlecode.concurrenttrees.radix.node.concrete._
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

import fr.acinq.bitcoin.{BinaryData, Script}
import rx.lang.scala.{Observable => Obs}


object Router { me =>
  type NodeIdSet = Set[PublicKey]
  type ShortChannelIdSet = Set[Long]
  type NodeChannelsMap = mutable.Map[PublicKey, ShortChannelIdSet]
  type CachedPaths = CachedAllDirectedPaths[PublicKey, ChanDirection]
  var finder = GraphFinder(mutable.Map.empty[ChanDirection, ChannelUpdate], 6)
  val black = mutable.Set.empty[PublicKey]
  val maps = new Mappings

  case class Node2Channels(mapping: NodeChannelsMap) {
    // For now we will just be proposing a most connected nodes first to the users
    lazy val seq = mapping.toSeq.sortWith { case (_ \ v1, _ \ v2) => v1.size > v2.size }

    def plusShortChannelId(info: ChanInfo) = {
      mapping(info.ca.nodeId1) += info.ca.shortChannelId
      mapping(info.ca.nodeId2) += info.ca.shortChannelId
      Node2Channels(mapping)
    }

    def minusShortChannelId(info: ChanInfo) = {
      mapping(info.ca.nodeId1) -= info.ca.shortChannelId
      mapping(info.ca.nodeId2) -= info.ca.shortChannelId
      val mapping1 = mapping.filter(_._2.nonEmpty)
      Node2Channels(mapping1)
    }
  }

  case class GraphFinder(updates: mutable.Map[ChanDirection, ChannelUpdate], maxPathLength: Int) {
    def outdatedChannels = updates.values.filter(_.lastSeen < System.currentTimeMillis - 86400 * 1000 * 14)
    def augmented(dir: ChanDirection, upd: ChannelUpdate) = GraphFinder(updates.updated(dir, upd), maxPathLength)
    private lazy val cachedGraph = refinedGraph(Set.empty, Set.empty)
    private val chanDirectionClass = classOf[ChanDirection]

    def safeFindPaths(withoutNodes: NodeIdSet, withoutChannels: ShortChannelIdSet, from: PublicKey, to: PublicKey) =
      Try apply doFindPaths(withoutNodes, withoutChannels, from, to) getOrElse Nil

    def doFindPaths(withoutNodes: NodeIdSet, withoutChannels: ShortChannelIdSet, from: PublicKey, to: PublicKey) =
      // Empty channels and nodes means this is a first path request so we can use a cache optimization here
      if (withoutNodes.isEmpty && withoutChannels.isEmpty) findPaths(cachedGraph, from, to)
      else findPaths(refinedGraph(withoutNodes, withoutChannels), from, to)

    private def findPaths(graph: CachedPaths, from: PublicKey, to: PublicKey) =
      for (path <- graph.getAllPaths(from, to, true, maxPathLength).asScala) yield
        for (dir <- path.getEdgeList.asScala.toVector) yield
          Hop(dir.from, dir.to, updates apply dir)

    private def refinedGraph(withoutNodes: NodeIdSet, withoutChannels: ShortChannelIdSet) = {
      val directedGraph = new DefaultDirectedGraph[PublicKey, ChanDirection](chanDirectionClass)

      for {
        direction <- updates.keys
        if !withoutChannels.contains(direction.channelId)
        if !withoutNodes.contains(direction.from)
        if !withoutNodes.contains(direction.to)
      } {
        Seq(direction.from, direction.to) foreach directedGraph.addVertex
        directedGraph.addEdge(direction.from, direction.to, direction)
      }

      // Paths without specified routes
      new CachedPaths(directedGraph)
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
    case node: NodeAnnouncement if maps.nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !maps.nodeId2Chans.mapping.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if node.addresses.isEmpty => Tools log s"Ignoring node without public addresses $node"
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

    maps isBadChannel info match {
      case None => maps addChanInfo info
      case Some(compromised: ChanInfo) =>
        val toRemove = List(compromised.ca.nodeId1, compromised.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
        complexRemove(toRemove.flatMap(maps.nodeId2Chans.mapping).map(maps.chanId2Info):_*)
        toRemove foreach black.add
    }
  }

  def complexRemove(infos: ChanInfo*) = me synchronized {
    for (chanInfoToRemove <- infos) maps rmChanInfo chanInfoToRemove
    // Once channel infos are removed we also have to remove all the affected updates
    // Removal also may result in lost nodes so all nodes with now zero channels are removed too
    maps.nodeId2Announce.filterKeys(maps.nodeId2Chans.mapping(_).isEmpty).values foreach maps.rmNode
    finder = GraphFinder(finder.updates.filter(maps.chanId2Info contains _._1.channelId), finder.maxPathLength)
    Tools log s"Removed channels: $infos"
  }

  // Channels may disappear silently without closing transaction on a blockchain so we must remove them too
  private def outdatedInfos: Iterable[ChanInfo] = finder.outdatedChannels.map(maps chanId2Info _.shortChannelId)
  Obs.interval(2.hours).foreach(_ => complexRemove(outdatedInfos.toSeq:_*), errLog)
}