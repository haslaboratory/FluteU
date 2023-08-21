//package mboom.cache
//
//import chisel3._
//import chiseltest._
//import mboom.axi.AXIRam
//import org.scalatest.freespec.AnyFreeSpec
//import org.scalatest.matchers.should.Matchers
//import mboom.util.BitMode.fromIntToBitModeLong
//import mboom.config.CPUConfig._
//import mboom.core.backend.decode.StoreMode
//import mboom.cache.components.WriteBackQueue
//
//class DataCacheWrapper extends Module {
//  val io = IO(new Bundle {
//    val core = new DCacheWithCore
//  })
//
//  val dcache = Module(new DataCacheNew(dCacheConfig))
//  val axiRam = Module(new AXIRam)
//
//  dcache.io.axi  <> axiRam.io.axi
//  dcache.io.core <> io.core
//}
//
//object DataCacheTestHelper {
//  def getMask(write: Boolean): Vec[Bool] = {
//    if (write) {
//      15.U(4.W).asTypeOf(Vec(4, Bool()))
//    } else {
//      0.U(4.W).asTypeOf(Vec(4, Bool()))
//    }
//  }
//}
//class testDataCache extends Module {
//  val io = IO(new Bundle() {})
//  val dcacheWrapper = Module(new DataCacheWrapper)
//
//  // test write back
//  val reqAddr = RegInit(VecInit("h9fc1a468".U(32.W), "h9fc1a460".U(32.W), "h9fc1a460".U(32.W), "h9fc1a468".U(32.W), "h9fc1a460".U(32.W), 32.U(32.W)))
//  val reqMask = RegInit(VecInit(DataCacheTestHelper.getMask(true),
//                                DataCacheTestHelper.getMask(true),
//                                DataCacheTestHelper.getMask(false),
//                                DataCacheTestHelper.getMask(false),
//                                DataCacheTestHelper.getMask(false),
//                                DataCacheTestHelper.getMask(false)))
//
//  val reqData = RegInit(VecInit(1122.U(32.W), 8877.U(32.W), 9845.U(32.W)))
//  val sp    = RegInit(0.U(32.W))
//  val timer = RegInit(0.U(32.W))
//  timer := timer + 1.U
//
//  dcacheWrapper.io.core.req.valid := sp < 6.U
//  dcacheWrapper.io.core.req.bits.addr      := reqAddr(sp)
//  dcacheWrapper.io.core.req.bits.validMask := reqMask(sp)
//  dcacheWrapper.io.core.req.bits.writeData := reqData(sp)
//  dcacheWrapper.io.core.flush := 0.B
//
//  when (dcacheWrapper.io.core.req.fire) {
//    sp := sp + 1.U
//  } .otherwise {
//
//  }
//
//  when (dcacheWrapper.io.core.resp.valid) {
//    printf(p"get: ${dcacheWrapper.io.core.resp.bits.loadData}\n")
//  }
//}
//
//class testGenerateData extends Module {
//  val io = IO(new Bundle() {})
//
//  val data2 = RegInit(VecInit(1122.U(32.W), 2233.U(32.W), 5566.U(32.W)))
//
//  val timer = RegInit(0.U(32.W))
//  when (timer < 2.U) {
//    timer := timer + 1.U
//  }
//
//  printf(p"${DataCacheUtils.generateWriteBack(0.U, data2(timer), 15.U.asTypeOf(Vec(4, Bool())))}\n")
//}
//
//class DataCacheTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
//  "test dcache" in {
//    test(new testDataCache).withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)) {
//      c => {
//        for (i <- 0 until 100) {
//          c.clock.step()
//        }
//
//      }
//    }
//  }
//}
//
