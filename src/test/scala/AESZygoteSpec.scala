import com.lightning.olympus.database.MongoDatabase
import com.lightning.walletapp.ln.Tools.random
import com.lightning.walletapp.ln.wire.AESZygote
import org.scalatest.FunSuite
import scodec.bits.ByteVector


class AESZygoteSpec extends FunSuite {
  val db = new MongoDatabase

  test("ByteVector matches") {
    val halfTxId = "0000000000000000"
    val aesz = AESZygote(1, ByteVector.view(random getBytes 16), ByteVector.view(random getBytes 32))
    db.putWatched(aesz, halfTxId)

    val res = db.getWatched(Vector(halfTxId))
    assert(res(halfTxId) === aesz)
  }
}
