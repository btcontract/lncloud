package com.btcontract.lncloud.crypto

import com.btcontract.lncloud.Utils.rand
import org.spongycastle.math.ec.ECPoint
import org.bitcoinj.core.ECKey
import java.math.BigInteger


// As seen on http://arxiv.org/pdf/1304.2094.pdf
class ECBlind(signerQ: ECPoint, signerR: ECPoint) {
  def params(number: Int) = List.fill(number)(makeParams)
  def tokens(number: Int) = List.fill(number)(oneToken)
  def oneToken = new BigInteger(1, rand getBytes 64)

  def makeParams: BlindParam = {
    val a = new ECKey(rand).getPrivKey
    val b = new ECKey(rand).getPrivKey
    val c = new ECKey(rand).getPrivKey

    val bInv = b.modInverse(ECKey.CURVE.getN)
    val abInvQ = signerQ.multiply(a.multiply(bInv) mod ECKey.CURVE.getN)
    val blindF = signerR.multiply(bInv).add(abInvQ).add(ECKey.CURVE.getG multiply c).normalize
    val isZero = blindF.getAffineXCoord.isZero | blindF.getAffineYCoord.isZero
    if (isZero) makeParams else BlindParam(blindF, a, b, c, bInv)
  }
}

// masterPub is signerQ
class ECBlindSign(masterPriv: BigInteger) {
  val masterPrivECKey = ECKey fromPrivate masterPriv
  val masterPubKeyHex = masterPrivECKey.getPublicKeyAsHex

  def blindSign(msg: BigInteger, k: BigInteger) =
    masterPriv.multiply(msg).add(k) mod ECKey.CURVE.getN

  def verifyClearSig(clearMessage: BigInteger, clearSignature: BigInteger, point: ECPoint) = {
    val rm = point.getAffineXCoord.toBigInteger mod ECKey.CURVE.getN multiply clearMessage mod ECKey.CURVE.getN
    ECKey.CURVE.getG.multiply(clearSignature) == masterPrivECKey.getPubKeyPoint.multiply(rm).add(point)
  }
}

// We blind messages but unblind their signatures
case class BlindParam(point: ECPoint, a: BigInteger, b: BigInteger, c: BigInteger, bInv: BigInteger) {
  def blind(msg: BigInteger) = b.multiply(keyBigInt mod ECKey.CURVE.getN).multiply(msg).add(a) mod ECKey.CURVE.getN
  def unblind(sigHat: BigInteger) = bInv.multiply(sigHat).add(c) mod ECKey.CURVE.getN
  def keyBigInt = point.getAffineXCoord.toBigInteger
}