package mboom.cache

import chisel3._
import chiseltest._
import mboom.axi.AXIRam
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import mboom.util.BitMode.fromIntToBitModeLong
import mboom.config.CPUConfig._
import mboom.config.CacheConfig

class InstrCacheWrapper extends Module {
  val io = IO(new Bundle {
    val core = new ICacheWithCore1
  })
  val icache = Module(new InstrCache(iCacheConfig))
  val axiRam = Module(new AXIRam)

  icache.io.axi <> axiRam.io.axi
  icache.io.core <> io.core
}

class testInstrCache extends Module {
  val io = IO(new Bundle() {})
  val icache = Module(new InstrCacheWrapper)

  val request = RegInit(VecInit(0.U(32.W), 4.U(32.W), 28.U(32.W), 0.U(32.W)))
  val sp      = RegInit(0.U(32.W))

  val timer = RegInit(0.U(32.W))
  timer := timer + 1.U

  icache.io.core.req.valid := sp < 3.U && icache.io.core.req.ready
  icache.io.core.req.bits.addr := request(sp)
  icache.io.core.flush   := timer === 2.U
  icache.io.core.resp.ready := 0.B
  when (icache.io.core.req.fire) {
     printf("add 1\n")
    sp := sp + 1.U
  } .otherwise {
     printf("add 0\n")
  }

  when (icache.io.core.resp.fire) {
    for (i <- 0 until fetchGroupSize+1) {
      when (icache.io.core.resp.bits.valid(i)) {
        printf(p"get:${i} ${Hexadecimal(icache.io.core.resp.bits.data(i))}\n")
      }
    }
  }
}
class InstrCacheTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
  "test icache" in {
    test(new testInstrCache()).withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)) {
      c => {
        for (i <- 0 until 100) {
          c.clock.step()
        }

      }
    }

  }
}