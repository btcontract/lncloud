package com.lightning.olympus

import com.lightning.walletapp.ln._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.RoutingInfoTag._
import com.googlecode.concurrenttrees.radix.node.concrete._
import scala.collection.JavaConverters._
import com.lightning.olympus.Utils._

import scala.util.{Success, Try}
import rx.lang.scala.{Observable => Obs}
import java.util.concurrent.ConcurrentLinkedQueue
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DirectedWeightedPseudograph
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.BinaryData
import scala.util.Random.shuffle
import scala.collection.mutable


case class ChanInfo(txid: String, capacity: Long, ca: ChannelAnnouncement)
case class ChanDirection(shortId: Long, from: PublicKey, to: PublicKey, weight: Long) {
  // We may have an object where only weight differs for a cusom comparator to omit weight

  override def equals(something: Any): Boolean = something match {
    case cd: ChanDirection => shortId == cd.shortId && from == cd.from
    case _ => false
  }
}

object Router { me =>
  type ShortChannelIdSet = Set[Long]
  type DefFactory = DefaultCharArrayNodeFactory
  type Graph = DirectedWeightedPseudograph[PublicKey, ChanDirection]
  private[this] val chanDirectionClass = classOf[ChanDirection]

  val chanId2Info = mutable.Map.empty[Long, ChanInfo]
  val txId2Info = mutable.Map.empty[BinaryData, ChanInfo]
  val nodeId2Announce = mutable.Map.empty[PublicKey, NodeAnnouncement]
  val unprocessedMessages = new ConcurrentLinkedQueue[LightningMessage]
  val searchTrie = new ConcurrentRadixTree[NodeAnnouncement](new DefFactory)
  var nodeId2Chans = Node2Channels(mutable.Map.empty withDefaultValue Set.empty)
  var finder = GraphFinder(Map.empty)

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

  case class Node2Channels(dict: mutable.Map[PublicKey, ShortChannelIdSet] = mutable.Map.empty) {
    // Too big nodes have a 50% change to get dampened down, relatively well connected nodes have a 10% chance to pop up
    def between(size: Long, min: Long, max: Long, chance: Double) = random.nextDouble < chance && size > min & size < max

    lazy val scoredNodeSuggestions = dict.toSeq.map {
      case key \ chanIds if between(chanIds.size, 300, Long.MaxValue, 0.5D) => key -> chanIds.size / 10
      case key \ chanIds if between(chanIds.size, 30, 300, 0.1D) => key -> chanIds.size * 10
      case key \ chanIds => key -> chanIds.size
    }.sortWith(_._2 > _._2).map(_._1)

    def plusShortChanId(info: ChanInfo) = {
      dict(info.ca.nodeId1) += info.ca.shortChannelId
      dict(info.ca.nodeId2) += info.ca.shortChannelId
      Node2Channels(dict)
    }

    def minusShortChanId(info: ChanInfo) = {
      dict(info.ca.nodeId1) -= info.ca.shortChannelId
      dict(info.ca.nodeId2) -= info.ca.shortChannelId
      // Remove empty mappings to not acumulate useless data
      val dict1 = dict filter { case _ \ set => set.nonEmpty }
      Node2Channels(dict1)
    }
  }

  case class GraphFinder(updates: Map[ChanDirection, ChannelUpdate] = Map.empty) {
    def chanUpdateIdentity(cu: ChannelUpdate) = s"${cu.cltvExpiryDelta}-${cu.htlcMinimumMsat}-${cu.feeBaseMsat}-${cu.feeProportionalMillionths}"
    def rmRandEdge(directions: Seq[ChanDirection], targetGraph: Graph) = runAnd(targetGraph)(targetGraph removeEdge shuffle(directions).head)
    val toHops: Vector[ChanDirection] => PaymentRoute = _.map(dir => updates(dir) toHop dir.from)
    // This works because every map update also replaces a GraphFinder object
    lazy val mixed = shuffle(updates.keys)

    // Given a node pubkey, find its most frequent ChannelUpdate paramters
    def mostFrequentChannelUpdate(nodeId: PublicKey) = updates.filterKeys(_.from == nodeId)
      .values.groupBy(chanUpdateIdentity).values.toList.sortBy(_.size).headOption.map(_.head)

    def findPaths(xn: Set[PublicKey], xc: ShortChannelIdSet, from: Set[PublicKey], to: PublicKey, sat: Long) = {
      // Filter out chans with insufficient capacity, nodes and chans excluded by user, not useful nodes and chans
      // We can't use rmRandomEdge if destination node has only one channel since it can possibly be removed
      val singleChanTarget = nodeId2Chans.dict(to).size == 1
      val baseGraph = new Graph(chanDirectionClass)

      def find(acc: PaymentRouteVec, graph: Graph, stop: Int, source: PublicKey): PaymentRouteVec =
        Try(DijkstraShortestPath.findPathBetween(graph, source, to).getEdgeList.asScala.toVector) match {
          case Success(way) if stop > 0 && way.size == 1 => find(acc :+ toHops(way), rmRandEdge(way, graph), stop - 1, source)
          case Success(way) if stop > 0 && singleChanTarget => find(acc :+ toHops(way), rmRandEdge(way dropRight 1, graph), stop - 1, source)
          case Success(way) if stop > 0 => find(acc :+ toHops(way), rmRandEdge(way.tail, graph), stop - 1, source)
          case Success(way) => acc :+ toHops(way)
          case _ => acc
        }

      mixed foreach {
        // Use sequence of guards for lazy evaluation
        case dir if chanId2Info(dir.shortId).capacity < sat =>
        case dir if xc.contains(dir.shortId) || xn.contains(dir.from) || xn.contains(dir.to) =>
        case dir if !from.contains(dir.from) && nodeId2Chans.dict(dir.from).size < 2 =>
        case dir if to != dir.to && nodeId2Chans.dict(dir.to).size < 2 =>

        case dir =>
          baseGraph.addVertex(dir.to)
          baseGraph.addVertex(dir.from)
          baseGraph.addEdge(dir.from, dir.to, dir)
          baseGraph.setEdgeWeight(dir, dir.weight)
      }

      val results = for {
        sourceNodeKey <- from
        clone = baseGraph.clone.asInstanceOf[Graph]
        // Create a separate graph for each source node
      } yield find(Vector.empty, clone, 3, sourceNodeKey)

      results.flatten
    }
  }

  def rescheduleQueue =
    Obs.just(Tools log "Rescheduling queue processing...")
      .delay(20.seconds).foreach(_ => processQueue, Tools.errlog)

  def processQueue: Unit = {
    val left = unprocessedMessages.size
    val nextMessage = unprocessedMessages.poll
    if (nextMessage == null) rescheduleQueue else {
      if (left % 100 == 0) Tools log s"$left msgs left"
      processMessage(nextMessage)
      processQueue
    }
  }

  private def processMessage(message: LightningMessage) = message match {
    // First channel announcements, then node announcements, then node updates

    case channelAnnounce: ChannelAnnouncement =>
      Blockchain getChanInfo channelAnnounce foreach {
        case small if small.capacity < values.minCapacity =>
          Tools log "Ignoring chan with low capacity"

        case chanInfo =>
          nodeId2Chans = nodeId2Chans plusShortChanId chanInfo
          chanId2Info(chanInfo.ca.shortChannelId) = chanInfo
          txId2Info(chanInfo.txid) = chanInfo
      }

    case node: NodeAnnouncement if node.addresses.isEmpty => Tools log s"Ignoring node without public addresses $node"
    case node: NodeAnnouncement if nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !nodeId2Chans.dict.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement => wrap(me addNode node)(me rmNode node) // Might be an update

    case cu: ChannelUpdate if !chanId2Info.contains(cu.shortChannelId) => Tools log s"Ignoring update without channels $cu"
    case cu: ChannelUpdate if isOutdated(cu) => Tools log s"Ignoring outdated update $cu"

    case cu: ChannelUpdate =>
      val info = chanId2Info(cu.shortChannelId)
      val isEnabled = Announcements isEnabled cu.channelFlags
      val (chainHeight, _, _) = fromShortId(cu.shortChannelId)
      // More fee: +weight, more capacity: -weight, more height: +weight
      val feeEstimate = cu.feeBaseMsat + cu.feeProportionalMillionths * 10
      val weight = feeEstimate + 50000000L / info.capacity + chainHeight / 500

      val direction = Announcements isNode1 cu.channelFlags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2, weight)
        case false => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1, weight)
      }

      val updates1 = finder.updates - direction
      val updates2 = if (isEnabled) updates1.updated(direction, cu) else updates1
      val isFresh = finder.updates.get(direction).forall(_.timestamp < cu.timestamp)
      if (isFresh) finder = GraphFinder(updates2)

    case _ =>
  }

  def complexRemove(infos: Iterable[ChanInfo], what: String) = me synchronized {
    // Once channel infos are removed we may have nodes without channels and updates

    for (info <- infos) {
      Tools log s"Removing channel with txid ${info.txid}"
      nodeId2Chans = nodeId2Chans minusShortChanId info
      chanId2Info -= info.ca.shortChannelId
      txId2Info -= info.txid
    }

    // Removal may result in lost nodes so all nodes with now zero channels are removed
    nodeId2Announce.filterKeys(nodeId => nodeId2Chans.dict(nodeId).isEmpty).values foreach rmNode
    // And finally we need to remove all the lost updates which have no channel announcements left
    val upd1 = finder.updates filter { case direction \ _ => chanId2Info contains direction.shortId }
    finder = GraphFinder(upd1)
    Tools log what
  }

  def isOutdated(cu: ChannelUpdate) =
    // Considered outdated if it is older than two weeks
    cu.timestamp < System.currentTimeMillis / 1000 - 1209600

  Obs.interval(5.minutes) foreach { _ =>
    // Removing directions also affects the next check since it makes nodes without chans
    val updates1 = finder.updates filterNot { case _ \ update => me isOutdated update }
    Tools log s"Had ${finder.updates.size} updates, ${updates1.size} updates now"
    finder = GraphFinder(updates1)
  }
}