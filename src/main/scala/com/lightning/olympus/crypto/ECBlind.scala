package com.lightning.olympus.crypto

import org.bitcoinj.core.ECKey.CURVE.{getG, getN}
import com.lightning.walletapp.ln.Tools.{Bytes, random}
import org.spongycastle.math.ec.ECPoint
import org.bitcoinj.core.ECKey
import java.math.BigInteger


// As seen on http://arxiv.org/pdf/1304.2094.pdf
class ECBlind(signerQ: ECPoint, signerR: ECPoint) {
  def params(number: Int) = List.fill(number)(makeParams)
  def tokens(number: Int) = List.fill(number)(oneToken)
  def oneToken = new BigInteger(1, random getBytes 64)

  def makeParams: BlindParam = {
    val a = new ECKey(random).getPrivKey
    val b = new ECKey(random).getPrivKey
    val c = new ECKey(random).getPrivKey

    val bInv: BigInteger = b modInverse getN
    val abInvQ: ECPoint = signerQ.multiply(a.multiply(bInv) mod getN)
    val blindF: ECPoint = signerR.multiply(bInv).add(abInvQ).add(getG multiply c).normalize
    if (blindF.getAffineXCoord.isZero | blindF.getAffineYCoord.isZero) makeParams
    else BlindParam(blindF getEncoded true, a, b, c, bInv)
  }
}

// We blind a token but unblind it's signature
case class BlindParam(point: Bytes, a: BigInteger, b: BigInteger, c: BigInteger, bInv: BigInteger) {
  def blind(msg: BigInteger): BigInteger = b.multiply(keyBigInt mod getN).multiply(msg).add(a) mod getN
  def keyBigInt: BigInteger = ECKey.CURVE.getCurve.decodePoint(point).getAffineXCoord.toBigInteger
  def unblind(sigHat: BigInteger): BigInteger = bInv.multiply(sigHat).add(c) mod getN
}

// masterPub is signerQ
class ECBlindSign(masterPriv: BigInteger) {
  val masterPrivECKey: ECKey = ECKey fromPrivate masterPriv
  val masterPubKeyHex: String = masterPrivECKey.getPublicKeyAsHex

  def blindSign(msg: BigInteger, k: BigInteger): BigInteger =
    masterPriv.multiply(msg).add(k) mod ECKey.CURVE.getN

  def verifyClearSig(clearMsg: BigInteger, clearSignature: BigInteger, point: ECPoint): Boolean = {
    val rm = point.getAffineXCoord.toBigInteger mod ECKey.CURVE.getN multiply clearMsg mod ECKey.CURVE.getN
    ECKey.CURVE.getG.multiply(clearSignature) == masterPrivECKey.getPubKeyPoint.multiply(rm).add(point)
  }
}