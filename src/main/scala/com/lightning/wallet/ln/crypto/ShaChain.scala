package com.lightning.wallet.ln.crypto

import com.lightning.wallet.ln.crypto.ShaChain._
import com.lightning.wallet.ln.LightningException
import com.lightning.wallet.ln.Tools.Bytes
import org.bitcoinj.core.Sha256Hash


case class ShaHashesWithIndex(hashes: IndexHashMap, lastIndex: Option[Long] = None)
case class Joint(parent: Option[Joint], value: Bytes, height: Int)

object ShaChain { me =>
  type Index = Vector[Boolean]
  type IndexHashMap = Map[Index, Bytes]
  val largestIndex = 0xffffffffffffffffL // For revocation preimages
  val largestTxIndex = 0xffffffffffffL // For per-commitment secrets

  // Each bool represents a move down the tree
  def flip(node: Joint): Bytes = flip(node.value, 63 - node.height)
  def flip(in: Bytes, index: Int): Bytes = in.updated(index / 8, in(index / 8).^(1 << index % 8).toByte)
  def deriveHash(node: Joint, right: Boolean): Bytes = if (right) Sha256Hash hash flip(node) else node.value
  def deriveChild(node: Joint, right: Boolean) = Joint(Some(node), deriveHash(node, right), node.height + 1)
  def shaChainFromSeed(hash: Bytes, idx: Long) = derive(Joint(None, hash, 0), me moves idx).value
  def derive(node: Joint, treeDirections: Index) = (node /: treeDirections)(deriveChild)
  def moves(index: Long) = Vector.range(63, -1, -1).map(i => index.&(1l << i) != 0)

  // Hashes are supposed to be received in reverse order so
  // we have parent :+ true which we should be able to recompute
  // since a left node's hash is the same as it's parent node's hash
  def doAddHash(hashes: IndexHashMap, hash: Bytes, index: Index): IndexHashMap =
    if (index.last) hashes.updated(index, hash) else index dropRight 1 match { case index1 =>
      val check = deriveChild(node = Joint(parent = None, hash, index1.length), right = true).value
      require(getHash(hashes)(index1 :+ true).forall(_ sameElements check), "Can not recompute")
      doAddHash(hashes - (index1 :+ false) - (index1 :+ true), hash, index1)
    }

  def addHash(shwi: ShaHashesWithIndex, hash: Bytes, index: Long) = {
    for (last <- shwi.lastIndex if index != last - 1) throw new LightningException
    ShaHashesWithIndex(doAddHash(shwi.hashes, hash, me moves index), Some apply index)
  }

  def getHash(hashes: IndexHashMap)(index: Index) =
    hashes.keys collectFirst { case idx if index startsWith idx =>
      val startingNode = Joint(None, hashes(idx), idx.length)
      derive(startingNode, index drop idx.length).value
    }
}