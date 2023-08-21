package mboom.mmu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.decode.MDUOp

// core控制TLB的接口
class TLBWithCore extends Bundle {
  val tlbReq   = new TLBReq
  val dataReq  = Input(UInt(20.W))
  val dataResp = Output(new TLBDataResp)
}

class TLBWithICache extends Bundle {
  val instrReq  = Input(UInt(20.W))
  val instrResp = Output(new TLBInstrResp)
}

class MMU extends Module {
  val io = IO(new Bundle {
    val core   = new TLBWithCore
    val cp0    = new TLBWithCP0

    val icache = new TLBWithICache
  })

  val tlb = Module(new TLB)

  tlb.io.instr.vpn := io.icache.instrReq
  tlb.io.data.vpn  := io.core.dataReq

  tlb.io.cp0   <> io.cp0
  tlb.io.write <> io.core.tlbReq.write
  tlb.io.read  <> io.core.tlbReq.read
  tlb.io.probe <> io.core.tlbReq.probe

  io.icache.instrResp.valid  := tlb.io.instr.valid
  io.icache.instrResp.pfn    := tlb.io.instr.pfn
  io.icache.instrResp.uncache := tlb.io.instr.uncache
  io.icache.instrResp.except := tlb.io.instrEx

  io.core.dataResp.uncache := tlb.io.data.uncache
  io.core.dataResp.pfn     := tlb.io.data.pfn
  io.core.dataResp.except  := tlb.io.dataEx

}
