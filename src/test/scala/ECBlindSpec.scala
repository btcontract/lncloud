import java.math.BigInteger

import com.lightning.olympus.Utils.values
import com.lightning.olympus.crypto.{BlindMemo, ECBlind}
import com.lightning.olympus.{BlindData, BlindTokens, Vals}
import org.scalatest.FunSuite
import com.lightning.walletapp.ln.Tools.random
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils.HEX
import scala.util.Random

    
class ECBlindSpec extends FunSuite {

  test("Blind tokens") {

    // Initialize system wide parameters
    values = Vals(privKey = "33337641954423495759821968886025053266790003625264088739786982511471995762588",
      btcApi = "http://foo:bar@127.0.0.1:18332", zmqApi = "tcp://127.0.0.1:29000", eclairSockIp = "192.210.203.16",
      eclairSockPort = 9735, eclairNodeId = "03dc39d7f43720c2c0f86778dfd2a77049fa4a44b4f0a8afb62f3921567de41375",
      rewindRange = 1, ip = "127.0.0.1", port = 9003, null, minCapacity = 50000L,
      sslFile = "/home/anton/Desktop/olympus/keystore.jks", sslPass = "pass123")

    // Session key pair for each request
    val signerRPriv = new ECKey(random)
    val signerRPup = ECKey.fromPublicOnly(signerRPriv.getPubKey)

    val bt = new BlindTokens
    // Resusable signing master key pair
    val signerQPub = bt.signer.masterPubKeyHex
    val signerQPup = ECKey.fromPublicOnly(HEX decode signerQPub)

    // Client generates a list of 100 blind tokens using signerR and signerQ pubkeys
    val ecBlind = new ECBlind(signerQPup.getPubKeyPoint, signerRPup.getPubKeyPoint)
    val memo = BlindMemo(ecBlind params 100, ecBlind tokens 100, signerRPup.getPublicKeyAsHex)
    val blindTokens = memo.makeBlindTokens

    // Server blind signs a list using signerR and signerQ privkeys
    val blindSigs = bt sign BlindData(null, "id", signerRPriv.getPrivKey, blindTokens.toVector)
    val blindSigsBigIntVec = for (sig <- blindSigs) yield new BigInteger(sig)

    // Clients unblinds signatures and makes a storable package
    val packed = memo.packEverything(memo.makeClearSigs(blindSigsBigIntVec:_*):_*)

    // Server successfully verifies each clear token
    assert(Random.shuffle(packed).map { case (point: String, clearToken: String, clearSig: String) =>
      bt.signer.verifyClearSig(clearMsg = new BigInteger(clearToken),
        clearSignature = new BigInteger(clearSig), point = bt decodeECPoint point)
    }.forall(identity))
  }
}
