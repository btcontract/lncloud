package com.lightning.olympus

import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._
import com.googlecode.concurrenttrees.radix.node.concrete._
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.ScriptPubKey
import org.jgrapht.alg.shortestpath.BidirectionalDijkstraShortestPath
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.lightning.wallet.ln.RoutingInfoTag.PaymentRoute
import com.lightning.wallet.ln.Scripts.multiSig2of2
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import com.lightning.wallet.ln.Tools.runAnd
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable

import com.lightning.wallet.ln.Tools.{random, wrap}
import fr.acinq.bitcoin.{BinaryData, Script}
import rx.lang.scala.{Observable => Obs}
import scala.util.{Success, Try}


case class ChanInfo(txid: String, key: ScriptPubKey, ca: ChannelAnnouncement)
case class ChanDirection(shortId: Long, from: PublicKey, to: PublicKey)

object Router { me =>
  type ShortChannelIdSet = Set[Long]
  type DefFactory = DefaultCharArrayNodeFactory
  type Graph = DefaultDirectedGraph[PublicKey, ChanDirection]
  private[this] val chanDirectionClass = classOf[ChanDirection]

  val blacklisted = mutable.Set.empty[PublicKey]
  val chanId2Info = mutable.Map.empty[Long, ChanInfo]
  val txId2Info = mutable.Map.empty[BinaryData, ChanInfo]
  val nodeId2Announce = mutable.Map.empty[PublicKey, NodeAnnouncement]
  val searchTrie = new ConcurrentRadixTree[NodeAnnouncement](new DefFactory)
  var nodeId2Chans = Node2Channels(Map.empty withDefaultValue Set.empty)
  var finder = GraphFinder(Map.empty)

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

  case class Node2Channels(dict: Map[PublicKey, ShortChannelIdSet] = Map.empty) {
    // Too big nodes have a 50% change to get dampened down, relatively well connected nodes have a 10% chance to pop up
    def between(size: Long, min: Long, max: Long, chance: Double) = random.nextDouble < chance && size > min & size < max

    lazy val defaultSuggestions = dict.toSeq.map {
      case key \ chanIds if between(chanIds.size, 300, Long.MaxValue, 0.5D) => key -> chanIds -> chanIds.size / 10
      case key \ chanIds if between(chanIds.size, 25, 75, 0.1D) => key -> chanIds -> chanIds.size * 10
      case key \ chanIds => key -> chanIds -> chanIds.size
    }.sortWith(_._2 > _._2).map(_._1._1)

    def plusShortChannelId(info: ChanInfo) = {
      val dict1 = dict.updated(info.ca.nodeId1, dict(info.ca.nodeId1) + info.ca.shortChannelId)
      val dict2 = dict1.updated(info.ca.nodeId2, dict1(info.ca.nodeId2) + info.ca.shortChannelId)
      Node2Channels(dict2)
    }

    def minusShortChannelId(info: ChanInfo) = {
      val dict1 = dict.updated(info.ca.nodeId1, dict(info.ca.nodeId1) - info.ca.shortChannelId)
      val dict2 = dict1.updated(info.ca.nodeId2, dict1(info.ca.nodeId2) - info.ca.shortChannelId)
      val dict3 = dict2 filterNot { case _ \ ids => ids.isEmpty }
      Node2Channels(dict3)
    }
  }

  case class GraphFinder(updates: Map[ChanDirection, ChannelUpdate] = Map.empty) {
    def rmVertex(graph: Graph, key: PublicKey) = runAnd(graph)(graph removeVertex key)
    def rmEdge(graph: Graph, dir: ChanDirection) = runAnd(graph)(graph removeEdge dir)

    def findPaths(xNodes: Set[PublicKey], xChans: ShortChannelIdSet, sources: Vector[PublicKey], destination: PublicKey) = {
      val toHops: Vector[ChanDirection] => PaymentRoute = directions => for (dir <- directions) yield updates(dir) toHop dir.from
      val commonDirectedGraph: Graph = new DefaultDirectedGraph[PublicKey, ChanDirection](chanDirectionClass)
      val perSource = math.ceil(24D / sources.size).toInt

      def find(acc: Vector[PaymentRoute], graph: Graph, limit: Int)(source: PublicKey): Vector[PaymentRoute] =
        Try apply BidirectionalDijkstraShortestPath.findPathBetween(graph, source, destination).getEdgeList.asScala.toVector match {
          case Success(way) if way.size > 2 && limit > 0 => find(acc :+ toHops(way), rmVertex(graph, way.head.to), limit - 1)(source)
          case Success(way) if way.size < 3 && limit > 0 => find(acc :+ toHops(way), rmEdge(graph, way.head), limit - 1)(source)
          case Success(way) => acc :+ toHops(way)
          case _ => acc
        }

      for {
        dir @ ChanDirection(shortId, from, to) <- updates.keys
        if from == destination || nodeId2Chans.dict(from).size > 1
        if to == destination || nodeId2Chans.dict(to).size > 1
        if !xChans.contains(shortId)
        if !xNodes.contains(from)
        if !xNodes.contains(to)

        _ = commonDirectedGraph addVertex to
        _ = commonDirectedGraph addVertex from
      } commonDirectedGraph.addEdge(from, to, dir)

      // Squash all route results into a single sequence
      // also use a single common pruned graph for all route searches
      sources flatMap find(Vector.empty, commonDirectedGraph, perSource)
    }
  }

  def receive(m: LightningMessage) = me synchronized doReceive(m)
  private def doReceive(message: LightningMessage) = message match {
    case ca: ChannelAnnouncement if isBlacklisted(ca) => Tools log s"Blacklisted $ca"
    case ca: ChannelAnnouncement if !Announcements.checkSigs(ca) => Tools log s"Ignoring invalid signatures $ca"
    case ca: ChannelAnnouncement => for (info <- Blockchain getInfo ca) updateOrBlacklistChannel(info)

    case node: NodeAnnouncement if blacklisted.contains(node.nodeId) => Tools log s"Ignoring $node"
    case node: NodeAnnouncement if node.addresses.isEmpty => Tools log s"Ignoring node without public addresses $node"
    case node: NodeAnnouncement if nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !nodeId2Chans.dict.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if !Announcements.checkSig(node) => Tools log s"Ignoring invalid signatures $node"
    case node: NodeAnnouncement => wrap(me addNode node)(me rmNode node) // Might be an update

    case cu: ChannelUpdate if cu.flags.data.size != 2 => Tools log s"Ignoring invalid flags length ${cu.flags.data.size}"
    case cu: ChannelUpdate if !chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"
    case cu: ChannelUpdate if isOutdated(cu) => Tools log s"Ignoring outdated update $cu"

    case cu: ChannelUpdate => try {
      val info = chanId2Info(cu.shortChannelId)
      val isDisabled = Announcements isDisabled cu.flags
      val direction = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      require(!isBlacklisted(info.ca), s"Ignoring blacklisted $cu")
      require(finder.updates.get(direction).forall(_.timestamp < cu.timestamp), s"Ignoring outdated $cu")
      require(Announcements.checkSig(cu, direction.from), s"Ignoring update with invalid signatures $cu")
      val updates1 = if (isDisabled) finder.updates - direction else finder.updates.updated(direction, cu)
      finder = finder.copy(updates = updates1)
    } catch errLog

    case _ =>
  }

  def complexRemove(infos: Iterable[ChanInfo], why: String) = me synchronized {
    // Once channel infos are removed we also have to remove all the affected updates
    // Removal also may result in lost nodes so all nodes with now zero channels are removed too

    for (chanInfoToRemove <- infos) rmChanInfo(chanInfoToRemove)
    nodeId2Announce.filterKeys(nodeId => nodeId2Chans.dict(nodeId).isEmpty).values foreach rmNode
    val updates1 = finder.updates filter { case direction \ _ => chanId2Info contains direction.shortId }
    finder = GraphFinder(updates1)
  }

  def updateOrBlacklistChannel(info: ChanInfo) = {
    // May fail because scripts don't match, may be blacklisted or added/updated
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.key.hex), s"Incorrect script $info")

    isBadChannel(info) match {
      case Some(compromised) =>
        val rm = List(compromised.ca.nodeId1, compromised.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
        complexRemove(rm flatMap nodeId2Chans.dict map chanId2Info, "Removed blacklisted channels")
        rm foreach blacklisted.add

      case None =>
        // Everything is fine
        addChanInfo(info)
    }
  }

  // Blacklist if same channel with valid sigs but different node ids
  def isOutdated(cu: ChannelUpdate) = cu.timestamp < System.currentTimeMillis / 1000 - 1209600 // 2 weeks
  def isBlacklisted(ca: ChannelAnnouncement) = blacklisted.contains(ca.nodeId1) || blacklisted.contains(ca.nodeId2)
  def isBadChannel(candidateInfo: ChanInfo): Option[ChanInfo] = chanId2Info.get(candidateInfo.ca.shortChannelId)
    .find(info => info.ca.nodeId1 != candidateInfo.ca.nodeId1 || info.ca.nodeId2 != info.ca.nodeId2)

  Obs interval 30.minutes foreach { _ =>
    val twoWeeksBehind = bitcoin.getBlockCount - 2016 // ~2 weeks
    val shortId2Updates = finder.updates.values.groupBy(_.shortChannelId)
    val oldChanInfos = chanId2Info.values.filter(_.ca.blockHeight < twoWeeksBehind)

    complexRemove(oldChanInfos filter {
      case info if !shortId2Updates.contains(info.ca.shortChannelId) => true
      case info => shortId2Updates(info.ca.shortChannelId) forall isOutdated
    }, "Removed outdated channels")
  }
}