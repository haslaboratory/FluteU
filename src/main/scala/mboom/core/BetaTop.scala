//package mboom.core
//
//import chisel3._
//import chisel3.util._
//import mboom.config.CPUConfig._
//import mboom.core.backend.rename.ArfView
//import mboom.cp0.CP0
//import chisel3.stage.ChiselStage
//import mboom.cache.{DCacheWithCore, ThroughDCache}
//import mboom.cache.top.ICacheForTestFetch
//import mboom.config.CacheConfig
//import mboom.core.backend.BackendRdy
//import mboom.core.frontend.{Frontend, FrontendWithBPU}
//
//class BetaTop(iFile: String, dFile: String) extends Module {
//  val io = IO(new Bundle {
//    val hwIntr = Input(UInt(6.W))
//    val pc     = Output(UInt(addrWidth.W))
//    val arf    = Output(Vec(archRegAmount, UInt(dataWidth.W)))
//    val count  = Output(UInt(dataWidth.W))
//  })
//
//  val frontend = Module(new FrontendWithBPU())
//  val backend  = Module(new BackendRdy)
//  val cp0      = Module(new CP0)
//  val dcache   = Module(new ThroughDCache)
//  val iCache   = Module(new ICacheForTestFetch(iFile))
//
//  backend.io.ibuffer <> frontend.io.out
//  frontend.io.branchCommit := backend.io.branchCommit
//  frontend.io.cp0.epc      := cp0.io.core.epc
//  frontend.io.cp0.eretReq  := backend.io.cp0.eret
//  frontend.io.cp0.intrReq  := cp0.io.core.intrReq
//  frontend.io.icache       <> iCache.io
//  io.pc                    := frontend.io.pc
//  cp0.io.hwIntr            := io.hwIntr
//  // TEMP //
//  cp0.io.core.read <> backend.io.cp0Read
//  cp0.io.core.write := backend.io.cp0Write
//  // ==== //
//  backend.io.cp0IntrReq := cp0.io.core.intrReq
//  backend.io.cp0 <> cp0.io.core.commit
//  backend.io.dcache <> dcache.io
//
//  // val arfView = Module(new ArfView)
//  // arfView.io.rmtIn := backend.io.rmt
//  // arfView.io.prf   := backend.io.prf
//
//  // DEBUG //
//  // io.count := cp0.io.debug.count
//  // ===== //
//
//  // io.arf := arfView.io.arfOut
//  io.arf := 0.U.asTypeOf(Vec(archRegAmount, UInt(dataWidth.W)))
//  io.count := 0.U(dataWidth.W)
//
//  // printf(p"fetch_pc:${Hexadecimal(frontend.io.pc)}\n")
//}
//
//object BetaTopGen extends App {
//  println("===== BataTop Gen Start =====")
//  (new ChiselStage).emitVerilog(
//    new BetaTop("test_data/xor.in", "test_data/zero.in"),
//    Array("--target-dir", "target/verilog", "--target:fpga")
//  )
//  println("===== BataTop Gen Complete =====")
//}
