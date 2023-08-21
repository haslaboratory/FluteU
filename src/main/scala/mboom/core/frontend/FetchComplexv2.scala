package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.cache.{ICacheResp, ICacheResp1, ICacheWithCore, ICacheWithCore1}
import mboom.components.SinglePortQueue
import mboom.core.frontend.BPU._

class FetchComplexv2 extends Module {
  assert(fetchGroupSize == 2)

  private val respQueueVolume: Int = 4

  val io = IO(new Bundle {
    val withDecode   = new FetchIONew()
    val branchCommit = Input(new BranchCommitWithBPU)
    val iCache       = Flipped(new ICacheWithCore1)
    val cp0          = Input(new FetchWithCP0)
    val pc           = Output(UInt(addrWidth.W))
  })

  val bpu       = Module(new BPUComplex(BPUParam(fetchGroupSize)))
  val respStage = RegInit(0.U.asTypeOf(new ICacheResp1))
  val bpuStage  = RegInit(0.U.asTypeOf(Valid(new BPUComplexResponse(BPUParam(fetchGroupSize)))))
  val preDec    = Module(new PreDecodeComplex)
  val ib        = Module(new Ibufferv2(new IBEntryNew, ibufferAmount, decodeWay, fetchGroupSize))

  val extFlush      = io.cp0.intrReq || io.cp0.eretReq || io.branchCommit.restore.address.valid
  val innerFlush    = preDec.io.innerFlush
  val needFlush     = innerFlush || extFlush
  val ibRoom        = ib.io.space
  val ibPermitCache = ibRoom > 6.U
  val cacheFree     = io.iCache.req.ready
  val stallFetch    = !cacheFree
  val stallPc       = !cacheFree || !ibPermitCache

  val pc     = RegInit("hbfc00000".U(32.W))
  val inSlot = RegInit(0.B)
  val dsAddr = RegInit(0.U(32.W))

  // pcGen
  val linearNpc = (pc & "hfffffff8".U(32.W)) + 8.U(32.W)

  val npc     = WireInit(pc)
  val nInSlot = WireInit(inSlot)
  val nDsAddr = WireInit(dsAddr)

  when (io.cp0.intrReq) {
    npc     := io.cp0.intrAddr
    nInSlot := false.B
  } .elsewhen(io.cp0.eretReq) {
    npc     := io.cp0.epc
    nInSlot := false.B
  } .elsewhen(io.branchCommit.restore.address.valid) {
    npc     := io.branchCommit.restore.address.bits
    nInSlot := false.B
  } .elsewhen(innerFlush) {
    npc     := preDec.io.redirAddr
    nInSlot := preDec.io.redirInSlot
    nDsAddr := preDec.io.redirDsAddr
  } .elsewhen (stallPc) {
    npc     := pc
    nInSlot := inSlot
  } .elsewhen(inSlot) {
    npc := dsAddr
    nInSlot := false.B
  }.elsewhen(bpu.io.nlp.taken(0) && (pc(2) === 0.U)) {
    npc     := bpu.io.nlp.bta(0)
    nInSlot := false.B
  } .elsewhen(bpu.io.nlp.taken(1)) {
    npc     := linearNpc
    nInSlot := true.B
    nDsAddr := bpu.io.nlp.bta(1)
  } .otherwise {
    npc     := linearNpc
    nInSlot := false.B
  }

  pc     := npc
  inSlot := nInSlot
  dsAddr := nDsAddr

  io.iCache.req.valid      := ibPermitCache
  io.iCache.req.bits.addr  := pc
  io.iCache.flush          := needFlush

  bpu.io.request.pc       := pc
  bpu.io.request.valid    := ibPermitCache
  bpu.io.request.stall    := stallFetch
  bpu.io.flush.extFlush   := extFlush
  bpu.io.flush.innerFlush := innerFlush

  val branchTrain = RegNext(io.branchCommit.train)

  for (i <- 0 until fetchGroupSize) yield {
    bpu.io.commit(i).valid         := branchTrain(i).valid
    bpu.io.commit(i).bits.pc       := branchTrain(i).bits.pc
    bpu.io.commit(i).bits.isBranch := branchTrain(i).bits.isBranch
    bpu.io.commit(i).bits.taken    := branchTrain(i).bits.taken
    bpu.io.commit(i).bits.br_type  := branchTrain(i).bits.br_type
    bpu.io.commit(i).bits.bta      := branchTrain(i).bits.bta
    bpu.io.commit(i).bits.flush    := branchTrain(i).bits.flush
  }

  io.iCache.resp.ready := 1.B
  respStage := io.iCache.resp.bits

  bpuStage.valid := bpu.io.response.valid && !stallFetch && !needFlush
  bpuStage.bits  := bpu.io.response.bits

  val resValid = bpuStage.valid && !innerFlush
  preDec.io.resValid := resValid
  preDec.io.bpu      := bpuStage.bits
  preDec.io.inst     := respStage
  preDec.io.extFlush := extFlush

  val ibEntries = Wire(Vec(fetchGroupSize, Valid(new IBEntryNew)))

  for (i <- 0 until fetchGroupSize) {
    ibEntries(i).valid          := preDec.io.outValid(i)
    ibEntries(i).bits.addr      := preDec.io.outPc(i)
    ibEntries(i).bits.inst      := preDec.io.outInst(i)
    ibEntries(i).bits.inSlot    := preDec.io.outInSlot(i)
    ibEntries(i).bits.predictBT := preDec.io.outTarget(i)
    ibEntries(i).bits.tlblRfl   := respStage.except.refill
    ibEntries(i).bits.tlblInv   := respStage.except.invalid
  }

  // preDecs -> ib
  for (i <- 0 until fetchGroupSize) {
    ib.io.write(i).valid := ibEntries(i).valid
    ib.io.write(i).bits  := ibEntries(i).bits
  }

  for (i <- 0 until fetchGroupSize) {
    io.withDecode.ibufferEntries(i) <> ib.io.read(i)
  }

  ib.io.flush := extFlush

  // TODO: iCache flush in the next cycle
  // val cacheFlush = RegNext(needFlush)
  io.iCache.flush := needFlush

  io.pc := pc
}
