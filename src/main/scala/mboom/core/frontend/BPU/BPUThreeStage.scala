package mboom.core.frontend.BPU

import chisel3._
import chisel3.util._
import mboom.core.frontend.BPU.component._
import mboom.config.CPUConfig._

case class BPUParam(n_ways: Int)
object BranchType {
  val width = 4

  val None = 0.U(width.W) // No update
  val DirectJump   = 1.U(width.W) // BTB selected
  val DirectCall   = 2.U(width.W) // BTB selected + CAS push
  val FuncReturn   = 3.U(width.W) // CAS selected(pop)
  val IndirectJump = 4.U(width.W) // Target Cache selected
  val IndirectCall = 5.U(width.W) // Target Cache selected + CAS push
  val Branch       = 6.U(width.W)
  val BranchCall   = 7.U(width.W)

  def needPush(b: UInt): Bool = {
    (b === DirectCall) || (b === IndirectCall)
  }

  def needPop(b: UInt): Bool = {
    (b === FuncReturn)
  }

  def selectBTB(b: UInt): Bool = {
    (b === DirectJump) || (b === DirectCall) || (b === Branch) || (b === BranchCall)
  }

  def selectCAS(b: UInt): Bool = {
    (b === FuncReturn)
  }

  def selectIndirect(b: UInt): Bool = {
    (b === IndirectJump) || (b === IndirectCall)
  }


  def fromLHP(b: UInt): Bool = {
    (b === Branch) || (b === BranchCall)
  }

  def fromBTB(b: UInt): Bool = {
    (b =/= None && b =/= Branch && b =/= BranchCall)
  }
}

class BPUCommitEntry extends Bundle {
  val pc       = UInt(addrWidth.W)
  val isBranch = Bool()
  val taken    = Bool()
  val br_type  = UInt(BranchType.width.W)
  val bta      = UInt(addrWidth.W)
  val flush    = Bool()
}

class BPUFlush extends Bundle {
  val innerFlush = Input(Bool())
  val extFlush = Input(Bool())
}

class BPUThreeStageRequest(param: BPUParam) extends Bundle {
  val pc        = Input(UInt(addrWidth.W))
  val stall     = Input(Bool())

  val outValid  = Output(Bool())
  val outPc     = Output(UInt(addrWidth.W))
  val delaySlot = Output(Bool())
  val taken     = Output(Vec(param.n_ways, Bool()))
  val bta       = Output(Vec(param.n_ways, UInt(addrWidth.W)))
}

// for test
class BPUThreeStage(param: BPUParam) extends Module {
  assert(param.n_ways == 2)

  private val nWays      = 2
  private val btbDepth   = 10
  private val bhtHistLen = 4
  private val bhtHashLen = 8

  val io = IO(new Bundle {
    val commit  = Input(Vec(param.n_ways, Valid(new BPUCommitEntry)))
    val request = new BPUThreeStageRequest(param)
    val flush   = new BPUFlush
  })

  val btb = Module(new BTBSync(BTBParam(nWays, btbDepth)))
  val bht = Module(new BHTAsync(BHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val pht = Module(new PHTAsync(PHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val ras = Module(new RAS1(param.n_ways))

  //// Request
  // Stage0 BTB BHT
  val flush = io.flush.innerFlush || io.flush.extFlush

  val reqSelect = io.request.pc(2).asUInt
  val reqPcVec = Mux(
    reqSelect.asBool,
    VecInit(io.request.pc + 4.U, io.request.pc),
    VecInit(io.request.pc      , io.request.pc)
  )

  btb.io.request.reqValid := !io.request.stall || flush
  btb.io.request.reqPc    := reqPcVec

  bht.io.request.reqValid := !io.request.stall || flush
  bht.io.request.reqPc    := reqPcVec

  // Stage1 PHT Target Cache
  val s1Valid    = RegInit(0.B)
  val s1Select   = RegInit(0.U(1.W))
  val s1Pc       = RegInit(0.U(addrWidth.W))
  val s1HistHash = RegInit(0.U.asTypeOf(Vec(param.n_ways, UInt((bhtHistLen+bhtHashLen).W))))

  val s1Stall = io.request.stall && !flush
  when (!s1Stall) {
    s1Valid    := 1.B
    s1Select   := reqSelect
    s1Pc       := io.request.pc
    s1HistHash := bht.io.request.reqHistHash
  }

  val s1Hit    = WireInit(btb.io.request.hit)
  val s1BrType = WireInit(btb.io.request.brType)
  val s1BtbPc  = WireInit(btb.io.request.nextPc)

  pht.io.request.reqValid := !io.request.stall || flush
  pht.io.request.reqHash  := s1HistHash
  val s1PhtTaken = WireInit(pht.io.request.taken)

  val s1Taken = WireInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val s1Bta   = WireInit(0.U.asTypeOf(Vec(param.n_ways, UInt(addrWidth.W))))

  val firstLast  = iCacheConfig.firstLast(s1Pc)
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

  val s1DelaySlot = ((firstLast && s1Taken(0)) || (secondLast && s1Taken(1)))

  // Stage2 RAS push pop
  // Caculate Branch Taken && Address
  val s2Valid     = RegInit(0.B)
  val s2Pc        = RegInit(0.U(addrWidth.W))
  val s2Taken     = RegInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val s2Bta       = RegInit(0.U.asTypeOf(Vec(param.n_ways, UInt(addrWidth.W))))
  val s2BrType    = RegInit(0.U.asTypeOf(Vec(param.n_ways, UInt(BranchType.width.W))))
  val s2DelaySlot = RegInit(0.B)

  val s2Stall = io.request.stall && !flush
  val s2FlushDelaySlot = s2Valid && s2Taken.reduce(_ || _) && !s2DelaySlot
  val s2FlushTaken     = s2Valid && s2Taken.reduce(_ || _) && s2DelaySlot

  when (!s2Stall) {
    s2Valid     := s1Valid && !flush && !s2FlushDelaySlot
    s2Pc        := s1Pc
    s2Taken     := Mux(s2FlushTaken, 0.U.asTypeOf(Vec(param.n_ways, Bool())), s1Taken)
    s2Bta       := s1Bta
    s2DelaySlot := s1DelaySlot
    s2BrType    := VecInit(s1BrType(s1Select), s1BrType(!s1Select))
  }

  io.request.outValid  := s2Valid
  io.request.outPc     := s2Pc
  io.request.taken     := s2Taken
  io.request.bta       := s2Bta
  io.request.delaySlot := s2DelaySlot

  val reqPush = (BranchType.needPush(s2BrType(0)) && s2Taken(0)) ||
    (BranchType.needPush(s2BrType(1)) && s2Taken(1))
  val reqPop = (BranchType.needPop(s2BrType(0)) && s2Taken(0)) ||
    (BranchType.needPop(s2BrType(1)) && s2Taken(1))
  val reqAddress = Mux(s2Taken(0), s2Pc + 8.U, s2Pc + 12.U)

  ras.io.address    := reqAddress
  ras.io.push_valid := s2Valid && !s2Stall && reqPush
  ras.io.pop_valid  := s2Valid && !s2Stall && reqPop

  //// commit
  val comSelect = io.commit(0).bits.pc(2).asUInt
  val comEntries = Mux(
    comSelect.asBool,
    VecInit(io.commit.reverse),
    io.commit
  )
  for (i <- 0 until param.n_ways) {
    bht.io.commit.comValid(i)    := comEntries(i).valid
    bht.io.commit.comIsBranch(i) := comEntries(i).bits.isBranch
    bht.io.commit.comTaken(i)    := comEntries(i).bits.taken
    bht.io.commit.comPc(i)       := comEntries(i).bits.pc
  }

  for (i <- 0 until param.n_ways) {
    btb.io.commit(i).valid        := comEntries(i).valid
    btb.io.commit(i).bits.pc      := comEntries(i).bits.pc
    btb.io.commit(i).bits.br_type := comEntries(i).bits.br_type
    btb.io.commit(i).bits.taken   := comEntries(i).bits.taken
    btb.io.commit(i).bits.bta     := comEntries(i).bits.bta
    btb.io.commit(i).bits.flush   := comEntries(i).bits.flush
  }

  val comValid    = RegInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val comIsBranch = RegInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val comTaken    = RegInit(0.U.asTypeOf(Vec(param.n_ways, Bool())))
  val comHistHash = RegInit(0.U.asTypeOf(Vec(param.n_ways, UInt((bhtHistLen+bhtHashLen).W))))

  for (i <- 0 until param.n_ways) {
    comValid(i)    := comEntries(i).valid
    comIsBranch(i) := comEntries(i).bits.isBranch
    comTaken(i)    := comEntries(i).bits.taken
    comHistHash(i) := bht.io.commit.comHistHash(i)
  }

  pht.io.commit.comValid    := comValid
  pht.io.commit.comIsBranch := comIsBranch
  pht.io.commit.comTaken    := comTaken
  pht.io.commit.comHistHash := comHistHash
}
