package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.cache.DataCachev3
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroLSUOp
import mboom.core.components.{RegFileExtReadIO, RegFileReadIO}
import mboom.mmu.TLBDataResp

// 将 DCache 集成在后端，减少 net delay
class LsuTop extends Module {
  val io = IO(new Bundle {
    val uop         = Flipped(DecoupledIO(new MicroLSUOp))
    val prfRead     = Flipped(new RegFileReadIO)
    val prfReadExt  = Flipped(new RegFileExtReadIO)

    val tlbDataReq  = Output(UInt(20.W))
    val tlbDataResp = Flipped(new TLBDataResp)

    val wb          = Output(new LsuWBv2)

    val stallReq    = Output(Bool())
//    val stallRobDiffer = Output(UInt(robEntryNumWidth.W))
//    val robHead     = Input(UInt(robEntryNumWidth.W))
    val potentialStallReg = Output(UInt(phyRegAddrWidth.W))

    val dRetire     = Input(Bool())
    val unRetire    = Input(Bool())

    val flush       = Input(new ExecFlush(nBranchCount))

    val dcacheAxi      = AXIIO.master()
    val dcacheRelease  = Output(Bool())
    val uncacheAxi     = AXIIO.master()
    val uncacheRelease  = Output(Bool())

    val debug_ls_fire = Output(Bool())
    val debug_ls_addr = Output(UInt(32.W))
    val debug_valid_mask = Output(UInt(4.W))
    val debug_store_data = Output(UInt(32.W))
    val debug_resp_valid = Output(Bool())
    val debug_load_data = Output(UInt(32.W))

    val debug_dcache_state = Output(UInt(4.W))
    val debug_out_num = Output(UInt(3.W))
  })

  val lsu    = Module(new LsuPipelinev3)
  val dcache = Module(new DataCachev3(dCacheConfig))

  lsu.io.uop     <> io.uop
  lsu.io.prfRead <> io.prfRead
  lsu.io.prfReadExt <> io.prfReadExt
  io.tlbDataReq  := lsu.io.tlbDataReq
  lsu.io.tlbDataResp := io.tlbDataResp

  io.wb.prf      := lsu.io.wb.prf
  io.wb.rob      := lsu.io.wb.rob
  io.wb.busyTableSpec := lsu.io.wb.busyTableSpec

  lsu.io.dRetire := io.dRetire
  lsu.io.unRetire := io.unRetire
  lsu.io.flush   := io.flush

  lsu.io.dcache  <> dcache.io.lsu
  dcache.io.tlbDataResp := io.tlbDataResp
  io.dcacheAxi   <> dcache.io.dcacheAxi
  io.dcacheRelease := dcache.io.dcacheRelease
  io.uncacheAxi  <> dcache.io.uncacheAxi
  io.uncacheRelease := dcache.io.uncacheRelease

  val stallNum = RegInit(0.U(4.W))
  when (dcache.io.stallReq) {
    when (stallNum < 2.U) {
      stallNum := stallNum + 1.U
    }
  } .otherwise {
    stallNum := 0.U
  }
  val corrRegValid = RegInit(0.B)
  val corrReg      = RegInit(0.U(phyRegAddrWidth.W))

  corrRegValid := lsu.io.wb.busyTableCorr.valid
  corrReg      := lsu.io.wb.busyTableCorr.bits

  val stallReq = RegInit(0.B)
  stallReq := dcache.io.stallReq && stallNum < 2.U

  io.stallReq := stallReq
  io.potentialStallReg := lsu.io.potentialStallReg
  io.wb.busyTableCorr.valid := stallReq && corrRegValid
  io.wb.busyTableCorr.bits  := corrReg
  io.wb.busyTableFact.valid := dcache.io.informValid && corrRegValid
  io.wb.busyTableFact.bits  := corrReg

  io.debug_ls_fire := lsu.io.dcache.valid && !lsu.io.dcache.stall
  io.debug_ls_addr := lsu.io.dcache.addr
  io.debug_valid_mask := lsu.io.dcache.validMask.asUInt
  io.debug_store_data := lsu.io.dcache.writeData
  io.debug_resp_valid := lsu.io.wb.prf.writeEnable
  io.debug_load_data  := lsu.io.wb.prf.writeData

  io.debug_dcache_state := dcache.io.debug_dcache_state
  io.debug_out_num      := dcache.io.debug_out_num
}
