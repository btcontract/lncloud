package com.btcontract.lncloud

import com.lightning.wallet.ln._
import com.lightning.wallet.ln.wire._

import scala.collection.JavaConverters._
import rx.lang.scala.{Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, Script, Transaction}
import com.btcontract.lncloud.Utils.{binData2PublicKey, errLog}
import com.lightning.wallet.helper.{SocketListener, SocketWrap}

import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory
import com.lightning.wallet.ln.wire.LightningMessageCodecs.PaymentRoute
import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import com.lightning.wallet.ln.Scripts.multiSig2of2
import java.util.concurrent.ConcurrentHashMap
import org.jgrapht.graph.DefaultDirectedGraph
import scala.concurrent.duration.DurationInt
import com.lightning.wallet.ln.crypto.Noise
import com.btcontract.lncloud.Utils.values
import scala.language.implicitConversions
import com.lightning.wallet.ln.Tools.none
import com.google.common.collect.Sets
import scala.collection.mutable
import java.net.InetAddress
import scala.util.Try


object Router { me =>
  // Blacklisted node ids
  val black = Sets.newConcurrentHashSet[BinaryData].asScala
  // Messages we need to process when there is nothing in `awaits`
  private val stash = Sets.newConcurrentHashSet[RoutingMessage].asScala
  // We need to check if each chan announcement has a bitcoin transaction
  private val awaits = Sets.newConcurrentHashSet[ChannelAnnouncement].asScala
  // Contains a cached graph to search for payment routes, is updated on new and deleted updates
  var finder = new GraphFinder(new ConcurrentHashMap[ChanDirection, ChannelUpdate].asScala, 7)
  val channels = new ChannelMappings
  val nodes = new NodesFinder

  class GraphFinder(val updates: mutable.Map[ChanDirection, ChannelUpdate], val maxPathLength: Int) {
    def outdatedChannels: Iterable[ChannelUpdate] = updates.values.filter(_.lastSeen < System.currentTimeMillis - 86400 * 1000 * 7 * 4)
    def augmented(dir: ChanDirection, upd: ChannelUpdate): GraphFinder = new GraphFinder(updates.updated(dir, upd), maxPathLength)
    def findRoutes(from: BinaryData, to: BinaryData): Seq[PaymentRoute] = Try apply findPaths(from, to) getOrElse Nil
    private lazy val directedGraph = new DefaultDirectedGraph[BinaryData, ChanDirection](chanDirectionClass)
    private lazy val chanDirectionClass = classOf[ChanDirection]

    private lazy val graph = updates.keys match { case keys =>
      for (direction <- keys) Seq(direction.from, direction.to) foreach directedGraph.addVertex
      for (direction <- keys) directedGraph.addEdge(direction.from, direction.to, direction)
      new CachedAllDirectedPaths[BinaryData, ChanDirection](directedGraph)
    }

    private def findPaths(from: BinaryData, to: BinaryData): Seq[PaymentRoute] =
      for (foundPath <- graph.getAllPaths(from, to, true, maxPathLength).asScala) yield
        for (dir <- foundPath.getEdgeList.asScala.toVector) yield
          Hop(dir.from, dir.to, updates apply dir)
  }

  class ChannelMappings {
    type ShortChannelIds = Set[Long]
    val chanId2Info: mutable.Map[Long, ChanInfo] = new ConcurrentHashMap[Long, ChanInfo].asScala
    val txId2Info: mutable.Map[BinaryData, ChanInfo] = new ConcurrentHashMap[BinaryData, ChanInfo].asScala
    val nodeId2Chans = new ConcurrentHashMap[BinaryData, ShortChannelIds].asScala.withDefaultValue(Set.empty)

    def rm(info: ChanInfo): Unit = {
      nodeId2Chans(info.ca.nodeId1) -= info.ca.shortChannelId
      nodeId2Chans(info.ca.nodeId2) -= info.ca.shortChannelId
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

  class NodesFinder {
    val searchTree = new ConcurrentRadixTree[NodeAnnouncement](new DefaultCharArrayNodeFactory)
    val nodeId2Announce = new ConcurrentHashMap[BinaryData, NodeAnnouncement].asScala

    def rm(node: NodeAnnouncement) =
      nodeId2Announce get node.nodeId foreach { found =>
        // Announce may have a new alias so we search for
        // an old one because nodeId should remain the same
        nodeId2Announce remove found.nodeId
        searchTree remove found.identifier
      }

    def add(newAnnounce: NodeAnnouncement) = {
      nodeId2Announce(newAnnounce.nodeId) = newAnnounce
      searchTree.put(newAnnounce.identifier, newAnnounce)
    }
  }

  private def resend = if (awaits.isEmpty) {
    // Create a temp collection, then remove those messages
    val temporary = for (message <- stash) yield message
    for (message <- temporary) stash remove message
    for (message <- temporary) me receive message
  }

  def receive(elem: LightningMessage): Unit = elem match {
    case ca: ChannelAnnouncement if black.contains(ca.nodeId1) || black.contains(ca.nodeId2) => Tools log s"Blacklisted $ca"
    case ca: ChannelAnnouncement if !Announcements.checkSigs(ca) => Tools log s"Ignoring invalid signatures $ca"
    case ca: ChannelAnnouncement =>

      awaits += ca
      Blockchain.getInfo(ca)
        .map(updateOrBlacklistChannel)
        .doOnTerminate(awaits -= ca)
        .doAfterTerminate(resend)
        .subscribe(none, errLog)

    case node: NodeAnnouncement if awaits.nonEmpty => stash add node
    case node: NodeAnnouncement if black.contains(node.nodeId) => Tools log s"Ignoring $node"
    case node: NodeAnnouncement if nodes.nodeId2Announce.get(node.nodeId).exists(_.timestamp >= node.timestamp) => Tools log s"Outdated $node"
    case node: NodeAnnouncement if !channels.nodeId2Chans.contains(node.nodeId) => Tools log s"Ignoring node without channels $node"
    case node: NodeAnnouncement if !Announcements.checkSig(node) => Tools log s"Ignoring invalid signatures $node"
    //case node: NodeAnnouncement if !Features.isSet(node.features, Features.CHANNELS_PUBLIC_BIT) => nodes rm node

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
      val channelDirection = Announcements isNode1 cu.flags match {
        case true => ChanDirection(cu.shortChannelId, info.ca.nodeId1, info.ca.nodeId2)
        case _ => ChanDirection(cu.shortChannelId, info.ca.nodeId2, info.ca.nodeId1)
      }

      require(!black.contains(info.ca.nodeId1) & !black.contains(info.ca.nodeId2), s"Ignoring $cu")
      require(finder.updates.get(channelDirection).forall(_.timestamp < cu.timestamp), s"Outdated $cu")
      require(Announcements.checkSig(cu, channelDirection.from), s"Ignoring invalid signatures for $cu")
      if (finder.updates contains channelDirection) finder.updates(channelDirection) = cu
      else finder = finder.augmented(channelDirection, upd = cu)
    } catch errLog

    case otherwise =>
      Tools.log(s"Unhandled $otherwise")
  }

  private def updateOrBlacklistChannel(info: ChanInfo): Unit = {
    // May fail because scripts don't match, may be blacklisted or added/updated
    val fundingOutScript = Script pay2wsh multiSig2of2(info.ca.bitcoinKey1, info.ca.bitcoinKey2)
    require(Script.write(fundingOutScript) == BinaryData(info.key.hex), s"Incorrect script in $info")

    channels isBad info map { old: ChanInfo =>
      val compromisedNodes = List(old.ca.nodeId1, old.ca.nodeId2, info.ca.nodeId1, info.ca.nodeId2)
      complexRemove(compromisedNodes.flatMap(channels.nodeId2Chans).map(channels.chanId2Info):_*)
      Tools log s"Compromised $info because $old exists"
      compromisedNodes map black.add
    } getOrElse channels.add(info)
  }

  def complexRemove(infos: ChanInfo*) = {
    for (channelInfo <- infos) channels rm channelInfo
    // Once channels are removed we also have to remove affected updates
    // Removal may result in lost nodes so all nodes with zero channels are removed
    nodes.nodeId2Announce.filterKeys(nodeId => channels.nodeId2Chans(nodeId).isEmpty).values.foreach(nodes.rm)
    finder = new GraphFinder(finder.updates.filter(channels.chanId2Info contains _._1.channelId), finder.maxPathLength)
  }

  private def outdatedInfos: Iterable[ChanInfo] = finder.outdatedChannels.map(channels chanId2Info _.shortChannelId)
  Obs.interval(6.hours).map(_ => outdatedInfos).filter(_.nonEmpty).foreach(infos => complexRemove(infos.toSeq:_*), errLog)
}

object RouterConnector {
  val socket = new SocketWrap(InetAddress getByName values.eclairIp, values.eclairPort)
  val keyPair = Noise.Secp256k1DHFunctions.generateKeyPair(Utils.random getBytes 32)
  val transportHandler = new TransportHandler(keyPair, values.eclairNodeId,
    Router receive LightningMessageCodecs.deserialize(_), socket)

  val socketListener = new SocketListener {
    override def onConnect = transportHandler.init
    override def onData(chunk: BinaryData) = transportHandler process chunk
    override def onDisconnect = Obs.just(Tools log "Restarting socket")
      .delay(10.seconds).doOnTerminate(socket.start).subscribe(none)
  }

  val transportListener = new StateMachineListener {
    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, TransportHandler.HANDSHAKE, TransportHandler.WAITING_CYPHERTEXT) =>
        val init = LightningMessageCodecs serialize Init("", localFeatures = "05")
        transportHandler process Tuple2(TransportHandler.Send, init)
    }

    override def onError = { case err =>
      Tools.log(s"Transport failure: $err")
      socket.shutdown
    }
  }

  val blockchainListener = new BlockchainListener {
    override def onNewTx(tx: Transaction) = for { input <- tx.txIn
      chanInfo <- Router.channels.txId2Info.get(input.outPoint.txid)
      if chanInfo.ca.outputIndex == input.outPoint.index
    } Router.complexRemove(chanInfo)

    override def onNewBlock(block: Block) = {
      val spent = Router.channels.txId2Info.values.filter(Blockchain.isSpent)
      if (spent.isEmpty) Tools log s"No spent channels at ${block.height}"
      else Router.complexRemove(spent.toSeq:_*)
    }
  }

  Blockchain.listeners += blockchainListener
  transportHandler.listeners += transportListener
  socket.listeners += socketListener
}