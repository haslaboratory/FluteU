package mboom.core.frontend.BPU

import chisel3._
import chisel3.util._
import mboom.core.frontend.BPU.component._
import mboom.config.CPUConfig._

class BPUComplexRequest(param: BPUParam) extends Bundle {
  val pc    = UInt(addrWidth.W)
  val valid = Bool()
  val stall = Bool()
}

class BPUComplexResponse(param: BPUParam) extends Bundle {
  val pc    = UInt(addrWidth.W)
  val taken = Vec(param.n_ways, Bool())
  val bta   = Vec(param.n_ways, UInt(addrWidth.W))
  val nlp   = new NLPResponse(param.n_ways)
}

class BPUComplex(param: BPUParam) extends Module {
  val io = IO(new Bundle {
    val request  = Input(new BPUComplexRequest(param))
    // 集成在 bpu 里
    val nlp      = Output(new NLPResponse(param.n_ways))
    val response = Valid(new BPUComplexResponse(param))
    val commit   = Input(Vec(param.n_ways, Valid(new BPUCommitEntry)))
    val flush    = new BPUFlush
  })

  private val nWays      = 2
  private val btbDepth   = 10
  private val bimDepth   = 10
  private val bhtHistLen = 4
  private val bhtHashLen = 8

  val btb = Module(new BTBAsync(BTBParam(nWays, btbDepth)))
  val bim = Module(new Bim(BimParam(nWays, bimDepth)))
  val bht = Module(new BHTAsync(BHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val pht = Module(new PHTAsync(PHTAsyncParam(nWays, bhtHashLen, bhtHistLen)))
  val ras = Module(new RAS1(param.n_ways))

  //// request
  val flush = io.flush.innerFlush || io.flush.extFlush

  val s0Valid = io.request.valid
  val s0Pc    = io.request.pc

  btb.io.request.reqValid := s0Valid
  btb.io.request.reqPc    := VecInit(Seq.fill(nWays)(s0Pc))
  bht.io.request.reqValid := s0Valid
  bht.io.request.reqPc    := VecInit(Seq.fill(nWays)(s0Pc))
  bim.io.request.reqValid := s0Valid
  bim.io.request.reqPc    := VecInit(Seq.fill(nWays)(s0Pc))

  val nlpResponse = Wire(new NLPResponse(param.n_ways))
  val pattern = RegInit(0.U(2.W))
  pattern := pattern + 1.U

  for (i <- 0 until nWays) {
    nlpResponse.taken(i) := bim.io.request.taken(i) && btb.io.request.hit(i)
    nlpResponse.bta(i)   := btb.io.request.nextPc(i)
  }
  io.nlp := nlpResponse

  val s1Valid    = RegInit(0.B)
  val s1Pc       = RegInit(0.U(addrWidth.W))
  val s1Hit      = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s1BrType   = RegInit(0.U.asTypeOf(Vec(nWays, UInt(BranchType.width.W))))
  val s1Target   = RegInit(0.U.asTypeOf(Vec(nWays, UInt(addrWidth.W))))
  val s1HistHash = RegInit(0.U.asTypeOf(Vec(nWays, UInt((bhtHistLen+bhtHashLen).W))))
  val s1Nlp      = RegInit(0.U.asTypeOf(new NLPResponse(param.n_ways)))

  val s1Stall = io.request.stall && !flush
  when (!s1Stall) {
    s1Valid    := s0Valid && !flush
    s1Hit      := btb.io.request.hit
    s1Pc       := s0Pc
    s1BrType   := btb.io.request.brType
    s1Target   := btb.io.request.nextPc
    s1HistHash := bht.io.request.reqHistHash
    s1Nlp      := nlpResponse
  }

  pht.io.request.reqValid := s1Valid
  pht.io.request.reqHash  := s1HistHash

  val bpuTaken = WireInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val bpuTarget = WireInit(0.U.asTypeOf(Vec(nWays, UInt(32.W))))

  val s1PcBase = s1Pc & "hfffffff0".U(32.W)

  for (i <- 0 until nWays) {
    bpuTaken(i) := s1Hit(i) && MuxCase(0.B, Seq(
      BranchType.fromLHP(s1BrType(i)) -> pht.io.request.taken(i),
      BranchType.fromBTB(s1BrType(i)) -> 1.B,
    ))
    bpuTarget(i) := MuxCase(s1PcBase + (8 + 4 * i).U(32.W), Seq(
      BranchType.selectBTB(s1BrType(i)) -> s1Target(i),
      BranchType.selectCAS(s1BrType(i)) -> ras.io.entry.address,
      BranchType.selectIndirect(s1BrType(i)) -> s1Target(i),
    ))
  }

  val s2Valid  = RegInit(0.B)
  val s2Pc     = RegInit(0.U(addrWidth.W))
  val s2Taken  = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s2BrType = RegInit(0.U.asTypeOf(Vec(nWays, UInt(BranchType.width.W))))

  val s2Stall = io.request.stall && !flush
  when (!s2Stall) {
    s2Valid  := s1Valid && !flush
    s2Pc     := Cat(s1Pc(31, 3), 0.U(3.W))
    s2Taken  := bpuTaken
    s2BrType := s1BrType
  }

  val reqPush = (BranchType.needPush(s2BrType(0)) && s2Taken(0)) ||
    (BranchType.needPush(s2BrType(1)) && s2Taken(1))
  val reqPop = (BranchType.needPop(s2BrType(0)) && s2Taken(0)) ||
    (BranchType.needPop(s2BrType(1)) && s2Taken(1))
  val reqAddress = Mux(s2Taken(0), s2Pc + 8.U, s2Pc + 12.U)

  ras.io.address    := reqAddress
  ras.io.push_valid := s2Valid && !io.request.stall && reqPush
  ras.io.pop_valid  := s2Valid && !io.request.stall && reqPop

  io.response.valid      := s1Valid
  io.response.bits.pc    := s1Pc
  io.response.bits.taken := bpuTaken
  io.response.bits.bta   := bpuTarget
  io.response.bits.nlp   := s1Nlp

  //// commit
  val exc = io.commit(0).bits.pc(2).asBool

  val comEntries = Mux(
    exc,
    VecInit(io.commit.reverse),
    io.commit
  )

  for (i <- 0 until param.n_ways) {
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

  for (i <- 0 until nWays) {
    bim.io.commit.comValid(i)    := comEntries(i).valid
    bim.io.commit.comIsJump(i)   := comEntries(i).bits.br_type =/= BranchType.None
    bim.io.commit.comTaken(i)    := comEntries(i).bits.taken
    bim.io.commit.comPc(i)       := comEntries(i).bits.pc
  }

  val comValid    = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comIsBranch = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comTaken    = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val comHistHash = RegInit(0.U.asTypeOf(Vec(nWays, UInt((bhtHistLen+bhtHashLen).W))))

  for (i <- 0 until param.n_ways) {
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
