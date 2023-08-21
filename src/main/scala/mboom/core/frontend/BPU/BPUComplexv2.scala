package mboom.core.frontend.BPU

import chisel3._
import chisel3.util._
import mboom.core.frontend.BPU.component._
import mboom.config.CPUConfig._

class BPUComplexv2Request(nWays: Int) extends Bundle {
  val s1Valid = Bool()
  val pc      = UInt(addrWidth.W)
  val stall   = Bool()
  val inSlot  = Bool()
}

class BPUComplexv2Response(nWays: Int) extends Bundle {
  val pc    = UInt(addrWidth.W)
  val taken = Vec(nWays, Bool())
  val bta   = Vec(nWays, UInt(addrWidth.W))
}


class BPUComplexv2 extends Module {
  private val nCommit    = 2
  private val nWays      = 4
  private val btbDepth   = 9
  private val bhtHistLen = 4
  private val bhtHashLen = 7

  val io = IO(new Bundle {
    val request  = Input(new BPUComplexv2Request(nWays))
    val response = Valid(new BPUComplexv2Response(nWays))
    val commit   = Input(Vec(nCommit, Valid(new BPUCommitEntry)))
    val flush    = new BPUFlush
  })

  val btb = Module(new BTBAsync(BTBParam(nWays, btbDepth)))
  val bht = Module(new BHTAsync(BHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val pht = Module(new PHTAsync(PHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val ras = Module(new RAS1(nWays))

  //// request
  val flush = io.flush.innerFlush || io.flush.extFlush

  val s0Valid  = io.request.stall
  val s0Pc     = io.request.pc
  val s0InSlot = io.request.inSlot

  btb.io.request.reqValid := s0Valid
  btb.io.request.reqPc    := VecInit(Seq.fill(nWays)(s0Pc))
  bht.io.request.reqValid := s0Valid
  bht.io.request.reqPc    := VecInit(Seq.fill(nWays)(s0Pc))


  val s1Valid    = RegInit(0.B)
  val s1Pc       = RegInit(0.U(addrWidth.W))
  val s1InSlot   = RegInit(0.B)
  val s1Hit      = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s1BrType   = RegInit(0.U.asTypeOf(Vec(nWays, UInt(BranchType.width.W))))
  val s1Target   = RegInit(0.U.asTypeOf(Vec(nWays, UInt(addrWidth.W))))
  val s1HistHash = RegInit(0.U.asTypeOf(Vec(nWays, UInt((bhtHistLen + bhtHashLen).W))))

  val s1Stall = io.request.stall && !flush
  when(!s1Stall) {
    s1Valid    := s0Valid && !flush
    s1Pc       := s0Pc
    s1InSlot   := s0InSlot
    s1Hit      := btb.io.request.hit
    s1BrType   := btb.io.request.brType
    s1Target   := btb.io.request.nextPc
    s1HistHash := bht.io.request.reqHistHash
  }

  pht.io.request.reqValid := s1Valid
  pht.io.request.reqHash := s1HistHash

  val bpuTaken = WireInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val bpuTarget = WireInit(0.U.asTypeOf(Vec(nWays, UInt(32.W))))

  val s1PcBase = s1Pc & "hfffffff0".U(32.W)

  for (i <- 0 until nWays) {
    bpuTaken(i) := s1Hit(i) && MuxCase(0.B, Seq(
      BranchType.fromLHP(s1BrType(i)) -> pht.io.request.taken(i),
      BranchType.fromBTB(s1BrType(i)) -> 1.B,
    ))
    bpuTarget(i) := MuxCase(s1PcBase + (8 + 4 * i).U(32.W), Seq(
      BranchType.selectBTB(s1BrType(i))      -> s1Target(i),
      BranchType.selectCAS(s1BrType(i))      -> ras.io.entry.address,
      BranchType.selectIndirect(s1BrType(i)) -> s1Target(i),
    ))
  }

  val s2Valid  = RegInit(0.B)
  val s2Pc     = RegInit(0.U(addrWidth.W))
  val s2InSlot = RegInit(0.B)
  val s2Taken  = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s2BrType = RegInit(0.U.asTypeOf(Vec(nWays, UInt(BranchType.width.W))))

  s2Valid  := s1Valid && !io.request.stall && !flush
  s2Pc     := s1Pc
  s2InSlot := s1InSlot
  s2Taken  := bpuTaken
  s2BrType := s1BrType

  val begin      = s2Pc(3, 2)
  val s2PcBase   = s2Pc & "hfffffff0".U(32.W)
  val reqPush    = WireInit(0.B)
  val reqPop     = WireInit(0.B)
  val reqAddress = WireInit(0.U(32.W))
  for (i <- nWays-1 to 0 by -1) {
    when ((begin <= i.U) && s2Taken(i)) {
      reqPush    := BranchType.needPush(s2BrType(i))
      reqPop     := BranchType.needPop(s2BrType(i))
      reqAddress := s2PcBase + (8 + 4 * i).U
    }
  }

  ras.io.address    := reqAddress
  ras.io.push_valid := s2Valid && reqPush && !s2InSlot
  ras.io.pop_valid  := s2Valid && reqPop  && !s2InSlot

  io.response.valid      := s1Valid
  io.response.bits.pc    := s1Pc
  io.response.bits.taken := bpuTaken
  io.response.bits.bta   := bpuTarget

  //// commit
  val comEntries = WireInit(0.U.asTypeOf(Vec(nWays, Valid(new BPUCommitEntry))))
  val sel = Cat(0.U(1.W), io.commit(0).bits.pc(3, 2))
  for (i <- 0 until nCommit) {
    val offset = (sel + i.U)(1, 0)
    comEntries(offset) := io.commit(i)
  }

  for (i <- 0 until nWays) {
    btb.io.commit(i).valid        := comEntries(i).valid
    btb.io.commit(i).bits.pc      := comEntries(i).bits.pc
    btb.io.commit(i).bits.br_type := comEntries(i).bits.br_type
    btb.io.commit(i).bits.taken   := comEntries(i).bits.taken
    btb.io.commit(i).bits.bta     := comEntries(i).bits.bta
    btb.io.commit(i).bits.flush   := comEntries(i).bits.flush
  }

  for (i <- 0 until nWays) {
    bht.io.commit.comValid(i)    := comEntries(i).valid
    bht.io.commit.comIsBranch(i) := comEntries(i).bits.isBranch
    bht.io.commit.comTaken(i)    := comEntries(i).bits.taken
    bht.io.commit.comPc(i)       := comEntries(i).bits.pc
  }

  val comValid    = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comIsBranch = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comTaken    = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comHistHash = RegInit(0.U.asTypeOf(Vec(nWays, UInt((bhtHistLen + bhtHashLen).W))))

  for (i <- 0 until nWays) {
    comValid(i)    := comEntries(i).valid
    comIsBranch(i) := comEntries(i).bits.isBranch
    comTaken(i)    := comEntries(i).bits.taken
    comHistHash(i) := bht.io.commit.comHistHash(i)
  }

  for (i <- 0 until nWays) {
    pht.io.commit.comValid(i)    := comValid(i)
    pht.io.commit.comIsBranch(i) := comIsBranch(i)
    pht.io.commit.comTaken(i)    := comTaken(i)
    pht.io.commit.comHistHash(i) := comHistHash(i)
  }
}
