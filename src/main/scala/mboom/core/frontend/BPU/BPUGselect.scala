package mboom.core.frontend.BPU

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{addrWidth, iCacheConfig}
import mboom.core.frontend.BPU.component.{BTBParam, BTBSync, PHTAsync, PHTAsyncParam, RAS1}

class BPUGselect(param: BPUParam) extends Module {
  assert(param.n_ways == 2)

  private val nWays      = 2
  private val btbDepth   = 10
  private val ghrHistLen = 4
  private val phtHashLen = 8

  val io = IO(new Bundle {
    val commit   = Input(Vec(param.n_ways, Valid(new BPUCommitEntry)))
    val request  = new BPUThreeStageRequest(param)
    val flush    = new BPUFlush
  })

  val btb = Module(new BTBSync(BTBParam(nWays, btbDepth)))
  val ghr = RegInit(0.U(ghrHistLen.W))
  val pht = Module(new PHTAsync(PHTAsyncParam(nWays, phtHashLen, ghrHistLen)))
  val ras = Module(new RAS1(param.n_ways))
  //// Request
  val flush = io.flush.innerFlush || io.flush.extFlush

  val reqSelect = io.request.pc(2).asUInt
  val reqPcVec  = Mux(
    reqSelect.asBool,
    VecInit(io.request.pc + 4.U, io.request.pc),
    VecInit(io.request.pc      , io.request.pc)
  )

  btb.io.request.reqValid := !io.request.stall || flush
  btb.io.request.reqPc    := reqPcVec

  // Stage1
  val s1Valid  = RegInit(0.B)
  val s1Select = RegInit(0.U(1.W))
  val s1Pc     = RegInit(0.U(addrWidth.W))

  val shiftGHR = Wire(UInt(ghrHistLen.W))

  val s1Stall = io.request.stall && !flush
  when (!s1Stall) {
    s1Valid   := 1.B
    s1Select  := reqSelect
    s1Pc      := io.request.pc
  }

  val s1Hit    = WireInit(btb.io.request.hit)
  val s1BrType = WireInit(btb.io.request.brType)
  val s1BtbPc  = WireInit(btb.io.request.nextPc)

  pht.io.request.reqValid := 1.B
  for (i <- 0 until nWays) {
    // pht.io.request.reqHash  := Cat(shiftGHR, )
  }
  val s1PhtTaken = WireInit(pht.io.request.taken)

  val s1Taken = WireInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val s1Bta = WireInit(0.U.asTypeOf(Vec(param.n_ways, UInt(addrWidth.W))))

  val firstLast = iCacheConfig.firstLast(s1Pc)
  val secondLast = iCacheConfig.secondLast(s1Pc)

  s1Taken(0) := s1Hit(s1Select) && MuxCase(0.B, Seq(
    BranchType.fromLHP(s1BrType(s1Select)) -> s1PhtTaken(s1Select),
    BranchType.fromBTB(s1BrType(s1Select)) -> 1.B,
  ))
  s1Bta(0) := MuxCase(s1Pc + 8.U(32.W), Seq(
    BranchType.selectBTB(s1BrType(s1Select)) -> s1BtbPc(s1Select),
    BranchType.selectCAS(s1BrType(s1Select)) -> ras.io.entry.address,
    BranchType.selectIndirect(s1BrType(s1Select)) -> s1BtbPc(s1Select),
  ))
  s1Taken(1) := s1Hit(!s1Select) && !firstLast && MuxCase(0.B, Seq(
    BranchType.fromLHP(s1BrType(!s1Select)) -> s1PhtTaken(!s1Select),
    BranchType.fromBTB(s1BrType(!s1Select)) -> 1.B,
  ))
  s1Bta(1) := MuxCase(s1Pc + 12.U(32.W), Seq(
    BranchType.selectBTB(s1BrType(!s1Select)) -> s1BtbPc(!s1Select),
    BranchType.selectCAS(s1BrType(!s1Select)) -> ras.io.entry.address,
    BranchType.selectIndirect(s1BrType(!s1Select)) -> s1BtbPc(!s1Select),
  ))

  // val hasBranch =

  // Stage2
}
