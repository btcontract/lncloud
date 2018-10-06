import com.lightning.olympus.database.MongoDatabase
import com.lightning.walletapp.ln.Tools.random
import com.lightning.walletapp.ln.wire.AESZygote
import org.scalatest.FunSuite


class AESZygoteSpec extends FunSuite {
  val db = new MongoDatabase

  test("BInary data matches") {
    val halfTxId = "0000000000000000"
    val aesz = AESZygote(1, random getBytes 16, random getBytes 32)
    db.putWatched(aesz, halfTxId)

    val res = db.getWatched(Vector(halfTxId))
    assert(res(halfTxId) === aesz)
  }
}
