package mboom.mmu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.decode.MDUOp
import mboom.cp0.EntryHiBundle


class TLB extends Module {
  val io = IO(new Bundle {
    val instr = new SearchPort
    val data  = new SearchPort
    val cp0   = new TLBWithCP0
    val write = new WritePort
    val read  = new ReadPort
    val probe = new ProbePort

    val instrEx = Output(new TLBInstrEx)
    val dataEx  = Output(new TLBDataEx)
  })

  val TLB_vpn    = RegInit(0.U.asTypeOf(Vec(tlbSize,UInt(19.W))))
  val TLB_asid   = RegInit(0.U.asTypeOf(Vec(tlbSize,UInt(8.W))))
  val TLB_global = RegInit(0.U.asTypeOf(Vec(tlbSize,Bool())))
  val TLB_pages  = RegInit(0.U.asTypeOf(Vec(tlbSize,Vec(2,new PhysicalPage))))

  val matchInstr = WireInit(VecInit(Seq.fill(tlbSize)(false.B)))
  val matchData = WireInit(VecInit(Seq.fill(tlbSize)(false.B)))

  // TODO: (frequency) asid 本地副本
  val entryHi = RegNext(io.cp0.regs.entryHi, 0.U.asTypeOf(new EntryHiBundle))

  val pageInstr = WireInit(0.U.asTypeOf(new PhysicalPage))
  val pageData  = WireInit(0.U.asTypeOf(new PhysicalPage))

  for (i <- 0 until tlbSize) {
    matchInstr(i) := (io.instr.vpn(19,1) === TLB_vpn(i) && (entryHi.asid === TLB_asid(i) || TLB_global(i)))
    matchData(i)  := (io.data.vpn(19,1) === TLB_vpn(i)  && (entryHi.asid === TLB_asid(i) || TLB_global(i)))
    when (matchInstr(i)) {
      pageInstr := TLB_pages(i)(io.instr.vpn(0))
    }
    when (matchData(i)) {
      pageData := TLB_pages(i)(io.data.vpn(0))
    }
  }

  val mappedInstr = (!io.instr.vpn(19)) || (io.instr.vpn(19, 18).andR)
  io.instr.valid    := (matchInstr.contains(true.B) && pageInstr.valid) || !mappedInstr
  io.instr.uncache :=
    (io.instr.vpn(19, 17) === "b101".U(3.W)) ||
    (io.instr.vpn(19, 17) === "b100".U(3.W) && io.cp0.regs.kseg0Uncached) ||
    (mappedInstr && pageInstr.cache === "b010".U(3.W))
  io.instr.pfn    := Mux(
    mappedInstr,
    pageInstr.pfn,
    Cat(0.U(3.W), io.instr.vpn(16, 0))
  )
  io.instr.dirty := pageInstr.dirty

  val mappedData = (!io.data.vpn(19)) || (io.data.vpn(19, 18).andR)
  io.data.valid := matchData.contains(true.B) || !mappedData
  io.data.uncache :=
    (io.data.vpn(19, 17) === "b101".U(3.W)) ||
    (io.data.vpn(19, 17) === "b100".U(3.W) && io.cp0.regs.kseg0Uncached) ||
    (mappedData && pageData.cache === "b010".U(3.W))
  io.data.pfn := Mux(
    mappedData,
    pageData.pfn,
    Cat(0.U(3.W), io.data.vpn(16, 0))
  )
  io.data.dirty := pageData.dirty

  // 取指异常
  io.instrEx.refill  := mappedInstr && !matchInstr.contains(true.B)
  io.instrEx.invalid := mappedInstr && matchInstr.contains(true.B) && !pageInstr.valid
  // 取数据异常
  io.dataEx.refill   := mappedData && !matchData.contains(true.B)
  io.dataEx.invalid  := mappedData && matchData.contains(true.B) && !pageData.valid
  io.dataEx.modified := mappedData && matchData.contains(true.B) && pageData.valid && !pageData.dirty

  // read
  io.read.read.vpn    := TLB_vpn(io.cp0.regs.index.index)
  io.read.read.asid   := TLB_asid(io.cp0.regs.index.index)
  io.read.read.global := TLB_global(io.cp0.regs.index.index)
  io.read.read.pages  := TLB_pages(io.cp0.regs.index.index)

  // probe
  // miss ???
  val probeMatch = WireInit(VecInit(Seq.fill(tlbSize)(false.B)))
  for(i <- 0 until tlbSize){
    probeMatch(i) := TLB_vpn(i) === io.cp0.regs.entryHi.vpn && (TLB_global(i) || TLB_asid(i) === io.cp0.regs.entryHi.asid)
  }
  io.probe.probe := Cat(
    !probeMatch.contains(true.B),
    0.U((32 - tlbWidth - 1).W),
    probeMatch.indexWhere((hit: Bool) => hit)
  )
  // write
  when (io.write.fromIndex) {
    TLB_vpn(io.cp0.regs.index.index)      := io.cp0.regs.entryHi.vpn
    TLB_asid(io.cp0.regs.index.index)     := io.cp0.regs.entryHi.asid
    TLB_global(io.cp0.regs.index.index)   := io.cp0.regs.entryLo0.global & io.cp0.regs.entryLo1.global
    TLB_pages(io.cp0.regs.index.index)(0) := io.cp0.regs.entryLo0
    TLB_pages(io.cp0.regs.index.index)(1) := io.cp0.regs.entryLo1
  }
  when (io.write.fromRandom) {
    TLB_vpn(io.cp0.regs.random.random)      := io.cp0.regs.entryHi.vpn
    TLB_asid(io.cp0.regs.random.random)     := io.cp0.regs.entryHi.asid
    TLB_global(io.cp0.regs.random.random)   := io.cp0.regs.entryLo0.global & io.cp0.regs.entryLo1.global
    TLB_pages(io.cp0.regs.random.random)(0) := io.cp0.regs.entryLo0
    TLB_pages(io.cp0.regs.random.random)(1) := io.cp0.regs.entryLo1
  }
}
