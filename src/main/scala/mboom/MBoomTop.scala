package mboom

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.cp0.CP0
import mboom.config.{CPUConfig, CacheConfig}
import mboom.core.backend._
import mboom.core.frontend.Frontend
import mboom.axi.AXIIO
import mboom.cache.{DataCachev2, InstrCache, InstrCachev2, InstrCachev3, UnCachev2}
import mboom.cache.axi._
import mboom.mmu.MMU

class MBoomTop extends Module {
  val io = IO(new Bundle {
    val hwIntr = Input(UInt(6.W))
    val pc     = Output(UInt(addrWidth.W))
    val axi    = AXIIO.master()
    // Debug

    val debug_rob_valid_0 = Output(Bool())
    val debug_rob_pc_0 = Output(UInt(32.W))
    val debug_rob_instr_0 = Output(UInt(32.W))
    val debug_rob_valid_1 = Output(Bool())
    val debug_rob_pc_1 = Output(UInt(32.W))
    val debug_rob_instr_1 = Output(UInt(32.W))

    val debug_ls_fire = Output(Bool())
    val debug_ls_addr = Output(UInt(32.W))
    val debug_valid_mask = Output(UInt(4.W))
    val debug_store_data = Output(UInt(32.W))
    val debug_resp_valid = Output(Bool())
    val debug_load_data = Output(UInt(32.W))

    val debug_exception = Output(UInt(11.W))
    val debug_badvaddr  = Output(UInt(32.W))

    val debug_pc         = Output(UInt(dataWidth.W))
    val debug_br_restore = Output(Bool())
    val debug_int        = Output(Bool())
    val debug_eret       = Output(Bool())

    val debug_aw_fire    = Output(Bool())
    val debug_aw_addr    = Output(UInt(32.W))
    val debug_w_fire     = Output(Bool())
    val debug_w_data     = Output(UInt(32.W))

    val debug_ar_fire = Output(Bool())
    val debug_ar_addr = Output(UInt(32.W))
    val debug_r_fire = Output(Bool())
    val debug_r_data = Output(UInt(32.W))

    val debug_fit_num = Output(UInt(8.W))
  })

  val frontend = Module(new Frontend())
  val backend  = Module(new Backendv2(2))
  val cp0      = Module(new CP0)
  // val dCache   = Module(new DataCachev2(dCacheConfig))
  val iCache   = Module(new InstrCachev2(iCacheConfig))
  // val unCache  = Module(new UnCachev2)

  val axiReadArbiter = Module(new AXIReadArbiter(masterCount = 3))
  val axiWriteArbiter = Module(new AXIWriteArbiter(masterCount = 2))

  val mmu = Module(new MMU)
  mmu.io.core <> backend.io.tlb

  mmu.io.cp0 <> cp0.io.tlb
  axiReadArbiter.io.bus.aw := DontCare
  axiReadArbiter.io.bus.w  := DontCare
  axiReadArbiter.io.bus.b  := DontCare

//  axiReadArbiter.io.masters(0).ar <> dCache.io.axi.ar // backend.io.dcacheAxi.ar
//  axiReadArbiter.io.masters(0).r <> dCache.io.axi.r // backend.io.dcacheAxi.r
//  axiReadArbiter.io.masters(0).aw := DontCare
//  axiReadArbiter.io.masters(0).w := DontCare
//  axiReadArbiter.io.masters(0).b := DontCare
//  axiReadArbiter.io.masters(1).ar <> unCache.io.axi.ar // backend.io.uncacheAxi.ar
//  axiReadArbiter.io.masters(1).r <> unCache.io.axi.r // backend.io.uncacheAxi.r
//  axiReadArbiter.io.masters(1).aw := DontCare
//  axiReadArbiter.io.masters(1).w := DontCare
//  axiReadArbiter.io.masters(1).b := DontCare
//  axiReadArbiter.io.masters(2).ar <> iCache.io.axi.ar
//  axiReadArbiter.io.masters(2).r <> iCache.io.axi.r
//  axiReadArbiter.io.masters(2).aw := DontCare
//  axiReadArbiter.io.masters(2).w := DontCare
//  axiReadArbiter.io.masters(2).b := DontCare
    axiReadArbiter.io.masters(0).ar <> backend.io.dcacheAxi.ar
    axiReadArbiter.io.masters(0).r <> backend.io.dcacheAxi.r
    axiReadArbiter.io.masters(0).aw := DontCare
    axiReadArbiter.io.masters(0).w := DontCare
    axiReadArbiter.io.masters(0).b := DontCare
    axiReadArbiter.io.masters(1).ar <> backend.io.uncacheAxi.ar
    axiReadArbiter.io.masters(1).r <> backend.io.uncacheAxi.r
    axiReadArbiter.io.masters(1).aw := DontCare
    axiReadArbiter.io.masters(1).w := DontCare
    axiReadArbiter.io.masters(1).b := DontCare
    axiReadArbiter.io.masters(2).ar <> iCache.io.axi.ar
    axiReadArbiter.io.masters(2).r <> iCache.io.axi.r
    axiReadArbiter.io.masters(2).aw := DontCare
    axiReadArbiter.io.masters(2).w := DontCare
    axiReadArbiter.io.masters(2).b := DontCare


  axiWriteArbiter.io.bus.ar := DontCare
  axiWriteArbiter.io.bus.r  := DontCare

//  axiWriteArbiter.io.masters(0).ar := DontCare
//  axiWriteArbiter.io.masters(0).r := DontCare
//  axiWriteArbiter.io.masters(0).aw <> unCache.io.axi.aw// backend.io.uncacheAxi.aw
//  axiWriteArbiter.io.masters(0).w <> unCache.io.axi.w// backend.io.uncacheAxi.w
//  axiWriteArbiter.io.masters(0).b <> unCache.io.axi.b// backend.io.uncacheAxi.b
//  axiWriteArbiter.io.masters(1).ar := DontCare
//  axiWriteArbiter.io.masters(1).r := DontCare
//  axiWriteArbiter.io.masters(1).aw <> dCache.io.axi.aw// backend.io.dcacheAxi.aw
//  axiWriteArbiter.io.masters(1).w <> dCache.io.axi.w// backend.io.dcacheAxi.w
//  axiWriteArbiter.io.masters(1).b <> dCache.io.axi.b// backend.io.dcacheAxi.b
//
//  axiWriteArbiter.io.release(0) := unCache.io.release// backend.io.uncacheRelease
//  axiWriteArbiter.io.release(1) := dCache.io.release// backend.io.dcacheRelease

  axiWriteArbiter.io.masters(0).ar := DontCare
  axiWriteArbiter.io.masters(0).r := DontCare
  axiWriteArbiter.io.masters(0).aw <> backend.io.uncacheAxi.aw
  axiWriteArbiter.io.masters(0).w <> backend.io.uncacheAxi.w
  axiWriteArbiter.io.masters(0).b <> backend.io.uncacheAxi.b
  axiWriteArbiter.io.masters(1).ar := DontCare
  axiWriteArbiter.io.masters(1).r := DontCare
  axiWriteArbiter.io.masters(1).aw <> backend.io.dcacheAxi.aw
  axiWriteArbiter.io.masters(1).w <> backend.io.dcacheAxi.w
  axiWriteArbiter.io.masters(1).b <> backend.io.dcacheAxi.b

  axiWriteArbiter.io.release(0) := backend.io.uncacheRelease
  axiWriteArbiter.io.release(1) := backend.io.dcacheRelease

  io.axi.ar <> axiReadArbiter.io.bus.ar
  io.axi.r <> axiReadArbiter.io.bus.r
  io.axi.aw <> axiWriteArbiter.io.bus.aw
  io.axi.w <> axiWriteArbiter.io.bus.w
  io.axi.b <> axiWriteArbiter.io.bus.b

  iCache.io.axi.aw := DontCare
  iCache.io.axi.w := DontCare
  iCache.io.axi.b := DontCare

  backend.io.ibuffer <> frontend.io.out
  frontend.io.branchCommit := backend.io.branchCommit
  frontend.io.cp0.epc      := cp0.io.core.epc
  frontend.io.cp0.eretReq  := cp0.io.core.eretReq
  frontend.io.cp0.intrAddr := cp0.io.core.excAddr
  frontend.io.cp0.intrReq  := cp0.io.core.intrReq
  frontend.io.icache <> iCache.io.core
  iCache.io.tlb <> mmu.io.icache
  io.pc         := frontend.io.pc
  cp0.io.hwIntr := io.hwIntr

  cp0.io.core.read <> backend.io.cp0Read
  cp0.io.core.write := backend.io.cp0Write
  cp0.io.core.tlbReq <> backend.io.cp0TLBReq

  backend.io.cp0IntrReq := cp0.io.core.intrReq
  backend.io.cp0EretReq := cp0.io.core.eretReq
  backend.io.cp0        <> cp0.io.core.commit
  backend.io.icacheInv  <> iCache.io.inv
//  backend.io.dcacheInv  <> dCache.io.inv
//  backend.io.uncache    <> unCache.io.core
//  backend.io.dcache     <> dCache.io.core

  // DEBUG //
  io.debug_rob_valid_0 := backend.io.debug_rob_valid_0
  io.debug_rob_pc_0    := backend.io.debug_rob_pc_0
  io.debug_rob_instr_0 := backend.io.debug_rob_instr_0
  io.debug_rob_valid_1 := backend.io.debug_rob_valid_1
  io.debug_rob_pc_1    := backend.io.debug_rob_pc_1
  io.debug_rob_instr_1 := backend.io.debug_rob_instr_1

  io.debug_ls_fire    := backend.io.debug_ls_fire
  io.debug_ls_addr    := backend.io.debug_ls_addr
  io.debug_valid_mask := backend.io.debug_valid_mask
  io.debug_store_data := backend.io.debug_store_data
  io.debug_resp_valid := backend.io.debug_resp_valid
  io.debug_load_data  := backend.io.debug_load_data

  io.debug_exception := backend.io.debug_exception
  io.debug_badvaddr  := backend.io.debug_badvaddr

  io.debug_pc         := frontend.io.pc
  io.debug_br_restore := backend.io.branchCommit.restore.address.valid
  io.debug_int        := cp0.io.core.intrReq
  io.debug_eret       := cp0.io.core.eretReq

  io.debug_aw_fire := backend.io.dcacheAxi.aw.valid && backend.io.dcacheAxi.aw.ready
  io.debug_aw_addr := backend.io.dcacheAxi.aw.bits.addr
  io.debug_w_fire  := backend.io.dcacheAxi.w.valid && backend.io.dcacheAxi.w.ready
  io.debug_w_data  := backend.io.dcacheAxi.w.bits.data

  io.debug_ar_fire := iCache.io.axi.ar.valid && iCache.io.axi.ar.ready
  io.debug_ar_addr := iCache.io.axi.ar.bits.addr
  io.debug_r_fire  := iCache.io.axi.r.valid && iCache.io.axi.r.ready
  io.debug_r_data  := iCache.io.axi.r.bits.data

  io.debug_fit_num := 0.U
}

object MBoomTopGen extends App {
  CPUConfig.buildVerilog = true
  (new chisel3.stage.ChiselStage)
    .emitVerilog(new MBoomTop, Array("--target-dir", "target/verilog/mboom", "--target:fpga"))
}
