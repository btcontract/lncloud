package com.lightning.olympus

import com.lightning.wallet.ln._
import com.lightning.olympus.Utils._
import com.lightning.wallet.ln.wire._
import scala.collection.JavaConverters._
import com.googlecode.concurrenttrees.radix.node.concrete._
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import com.lightning.wallet.ln.RoutingInfoTag.PaymentRoute
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import com.lightning.wallet.ln.Scripts.multiSig2of2
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable

import com.lightning.wallet.ln.Tools.{random, wrap}
import fr.acinq.bitcoin.{BinaryData, Script}
import rx.lang.scala.{Observable => Obs}
import scala.util.{Success, Try}


object Router { me =>
  type NodeIdSet = Set[PublicKey]
  type ShortChannelIdSet = Set[Long]
  type ChanInfos = Iterable[ChanInfo]
  type NodeChannelsMap = mutable.Map[PublicKey, ShortChannelIdSet]
  private val chanDirectionClass = classOf[ChanDirection]
  val black = mutable.Set.empty[PublicKey]
  var finder = GraphFinder(Map.empty)
  val maps = new Mappings

  case class GraphFinder(updates: Map[ChanDirection, ChannelUpdate] = Map.empty) {
    // This class is intended to be fully replaced with an updated copy of graph map
    // each time an existing deirection is disabled, outdated or added by an update

    def findPaths(ns: NodeIdSet, cs: ShortChannelIdSet, from: PublicKey, to: PublicKey,
                  left: Int, acc: Vector[PaymentRoute] = Vector.empty): Vector[PaymentRoute] =

      Try apply findPaths(refined(ns, cs), from, to) match {
        case Success(paymentRoute) if paymentRoute.size > 2 && left > 0 =>
          val nodesWithRemovedPeersPeer: NodeIdSet = ns + paymentRoute.tail.head.nodeId
          findPaths(nodesWithRemovedPeersPeer, cs, from, to, left - 1, acc :+ paymentRoute)

        case Success(paymentRoute) if paymentRoute.size <= 2 && left > 0 =>
          val chansWithoutDirect: ShortChannelIdSet = cs + paymentRoute.head.shortChannelId
          findPaths(ns, chansWithoutDirect, from, to, left - 1, acc :+ paymentRoute)

        // Either no more attempts left or a failure
        case Success(paymentRoute) => acc :+ paymentRoute
        case _ => acc
      }

    private def findPaths(graph: DefaultDirectedGraph[PublicKey, ChanDirection], from: PublicKey, to: PublicKey) =
      for (direction <- DijkstraShortestPath.findPathBetween(graph, from, to).getEdgeList.asScala.toVector)
        yield updates(direction) toHop direction.from

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
        if !withoutChannels.contains(direction.shortId)
        if !withoutNodes.contains(direction.from)
        if !withoutNodes.contains(direction.to)
      } insert(direction)
      directedGraph
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

  case class Node2Channels(nodeMap: NodeChannelsMap) {
    def between(v: Long, min: Long, max: Long) = v > min & v < max
    // Relatively well connected nodes have a 0.1 chance to pop up
    // Too big nodes have a 0.3 change to get dampened down

    lazy val seq = nodeMap.toSeq.map {
      case core @ (_, chanIds) if random.nextDouble < 0.3D && between(chanIds.size, 500, Long.MaxValue) => (core, chanIds.size / 10)
      case core @ (_, chanIds) if random.nextDouble < 0.1D && between(chanIds.size, 50, 200) => (core, chanIds.size * 10)
      case core @ (_, chanIds) => (core, chanIds.size)
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

  def receive(msg: LightningMessage) = me synchronized doReceive(msg)
  private def doReceive(message: LightningMessage): Unit = message match {
    case ca: ChannelAnnouncement if black.contains(ca.nodeId1) || black.contains(ca.nodeId2) => Tools log s"Blacklisted $ca"
    case ca: ChannelAnnouncement if !Announcements.checkSigs(ca) => Tools log s"Ignoring invalid signatures $ca"
    case ca: ChannelAnnouncement => for (info <- Blockchain getInfo ca) updateOrBlacklistChannel(info)

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
      val chanDirection = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      require(!black.contains(info.ca.nodeId1) & !black.contains(info.ca.nodeId2), s"Ignoring $cu")
      require(finder.updates.get(chanDirection).forall(_.timestamp < cu.timestamp), s"Outdated $cu")
      require(Announcements.checkSig(cu, chanDirection.from), s"Ignoring invalid signatures for $cu")
      require(notProportionalOutlier(cu), s"Ignoring feeProportionalMillionths outlier $cu")
      require(notBaseOutlier(cu), s"Ignoring feeBaseMsat outlier $cu")

      val updates1 =
        // A node MAY create and send a channel_update with the disable bit set
        if (Announcements isDisabled cu.flags) finder.updates - chanDirection
        else finder.updates.updated(chanDirection, cu)

      // Updaing should replace a finder
      finder = finder.copy(updates = updates1)
    } catch errLog

    case _ =>
  }

  def complexRemove(infos: ChanInfos, why: String) = me synchronized {
    // Once channel infos are removed we also have to remove all the affected updates
    // Removal also may result in lost nodes so all nodes with now zero channels are removed too

    for (chanInfoToRemove <- infos) maps rmChanInfo chanInfoToRemove
    maps.nodeId2Announce.filterKeys(maps.nodeId2Chans.nodeMap(_).isEmpty).values foreach maps.rmNode
    finder = GraphFinder apply finder.updates.filter(maps.chanId2Info contains _._1.shortId)
    Tools log s"$why: $infos"
  }

  private def updateOrBlacklistChannel(info: ChanInfo): Unit = {
    // May fail because scripts don't match, may be blacklisted or added/updated
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.key.hex), s"Incorrect script in $info")

    maps.isBadChannel(info) map { compromised =>
      val toRemove = List(compromised.ca.nodeId1, compromised.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
      complexRemove(toRemove.flatMap(maps.nodeId2Chans.nodeMap) map maps.chanId2Info, "Removed blacklisted")
      for (blackKey <- toRemove) yield black add blackKey
    } getOrElse maps.addChanInfo(info)
  }

  // At start these are true, updated later
  var notBaseOutlier = (cu: ChannelUpdate) => true
  var notProportionalOutlier = (cu: ChannelUpdate) => true
  val baseFeeStat = new Statistics[ChannelUpdate] { def extract(item: ChannelUpdate) = item.feeBaseMsat.toDouble }
  val proportionalFeeStat = new Statistics[ChannelUpdate] { def extract(item: ChannelUpdate) = item.feeProportionalMillionths.toDouble }

  private def outlierInfos = {
    // Update feeBaseMsat checker
    val updates = finder.updates.values
    val baseFeeMean = baseFeeStat mean updates
    val baseFeeStdDev = math sqrt baseFeeStat.variance(updates, baseFeeMean)
    notBaseOutlier = baseFeeStat.notOutlier(baseFeeMean, baseFeeStdDev, 2)

    // Update feeProportionalMillionths checkes
    val proportionalFeeMean = proportionalFeeStat mean updates
    val proportionalFeeStdDev = math sqrt proportionalFeeStat.variance(updates, proportionalFeeMean)
    notProportionalOutlier = proportionalFeeStat.notOutlier(proportionalFeeMean, proportionalFeeStdDev, 3)

    // Filter out outliers
    val baseOutliers = updates filterNot notBaseOutlier
    val proportionalOutliers = updates filterNot notProportionalOutlier
    (baseOutliers ++ proportionalOutliers).map(maps chanId2Info _.shortChannelId)
  }

  // Channels may disappear without a closing on-chain transaction so we must disable them once we detect that
  private def isOutdated(cu: ChannelUpdate) = cu.timestamp < System.currentTimeMillis / 1000 - 1209600 // 2 weeks
  private def outdatedInfos = finder.updates.values.filter(isOutdated).map(maps chanId2Info _.shortChannelId)
  Obs.interval(12.hours).foreach(_ => complexRemove(outdatedInfos, "Removed outdated channels"), errLog)
  Obs.interval(2.minutes).foreach(_ => complexRemove(outlierInfos, "Removed outliers channels"), errLog)
}