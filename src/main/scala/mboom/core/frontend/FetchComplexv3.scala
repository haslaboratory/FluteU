package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.cache.{ICacheResp, ICacheResp1, ICacheWithCore, ICacheWithCore1}
import mboom.components.SinglePortQueue
import mboom.core.frontend.BPU._

class FetchComplexv3 extends Module {
  private val nWays = 4
  private val respQueueVolume: Int = 4

  val io = IO(new Bundle {
    val withDecode   = new FetchIONew()
    val branchCommit = Input(new BranchCommitWithBPU)
    val iCache       = Flipped(new ICacheWithCore1)
    val cp0          = Input(new FetchWithCP0)
    val pc           = Output(UInt(addrWidth.W))
  })

  val bpu      = Module(new BPUComplexv2())
  val respQ    = Module(new SinglePortQueue(new ICacheResp1, respQueueVolume, hasFlush = true))
  val bpuStage = RegInit(0.U.asTypeOf(Valid(new BPUComplexv2Response(nWays))))
  val preDec   = Module(new PreDecodeComplexv2)
  val ib       = Module(new Ibufferv2(new IBEntryNew, ibufferAmount, decodeWay, nWays))

  val reserveRoom = RegInit(4.U(4.W))

  val extFlush      = io.cp0.intrReq || io.cp0.eretReq || io.branchCommit.restore.address.valid
  val innerFlush    = preDec.io.innerFlush
  val needFlush     = innerFlush || extFlush
  val ibRoom        = ib.io.space
  val ibPermitCache = ibRoom > reserveRoom
  val cacheFree     = io.iCache.req.ready
  val stallFetch    = !cacheFree || !ibPermitCache

  val pc = RegInit("hbfc00000".U(32.W))
  val inSlot = RegInit(0.B)
  val dsAddr = RegInit(0.U(32.W))

  // pcGen
  val linearNpc = (pc & "hfffffff0".U(32.W)) + 16.U(32.W)

  val npc = WireInit(pc)
  val nInSlot = WireInit(inSlot)
  when(io.cp0.intrReq) {
    npc := io.cp0.intrAddr
    nInSlot := false.B
  }.elsewhen(io.cp0.eretReq) {
    npc := io.cp0.epc
    nInSlot := false.B
  }.elsewhen(io.branchCommit.restore.address.valid) {
    npc := io.branchCommit.restore.address.bits
    nInSlot := false.B
  }.elsewhen(innerFlush) {
    npc := preDec.io.redirAddr
    nInSlot := preDec.io.redirInSlot
  }.elsewhen(stallFetch) {
    npc := pc
    nInSlot := inSlot
  }.elsewhen(inSlot) {
    npc := dsAddr
    nInSlot := false.B
  }.otherwise {
    npc := linearNpc
    nInSlot := false.B
  }

  pc := npc
  inSlot := nInSlot
  when(preDec.io.innerFlush) {
    dsAddr := preDec.io.redirDsAddr
  }

  when (needFlush) {
    reserveRoom := 4.U
  } .elsewhen(!stallFetch && reserveRoom < 12.U) {
    reserveRoom := reserveRoom + 4.U
  }

  io.iCache.req.valid := ibPermitCache
  io.iCache.req.bits.addr := pc
  io.iCache.flush := needFlush

  bpu.io.request.pc       := pc
  bpu.io.request.stall    := stallFetch
  bpu.io.request.inSlot   := inSlot
  bpu.io.flush.extFlush   := extFlush
  bpu.io.flush.innerFlush := innerFlush
  for (i <- 0 until fetchGroupSize) yield {
    bpu.io.commit(i).valid := io.branchCommit.train(i).valid
    bpu.io.commit(i).bits.pc := io.branchCommit.train(i).bits.pc
    bpu.io.commit(i).bits.isBranch := io.branchCommit.train(i).bits.isBranch
    bpu.io.commit(i).bits.taken := io.branchCommit.train(i).bits.taken
    bpu.io.commit(i).bits.br_type := io.branchCommit.train(i).bits.br_type
    bpu.io.commit(i).bits.bta := io.branchCommit.train(i).bits.bta
    bpu.io.commit(i).bits.flush := io.branchCommit.train(i).bits.flush
  }

  io.iCache.resp.ready := 1.B
  respQ.io.enq.valid := io.iCache.resp.valid
  respQ.io.enq.bits := io.iCache.resp.bits

  bpuStage.valid := bpu.io.response.valid && !stallFetch && !needFlush
  bpuStage.bits := bpu.io.response.bits

  respQ.io.deq.ready := bpuStage.valid
  respQ.io.flush.get := needFlush

  val resValid = bpuStage.valid && respQ.io.deq.valid
  preDec.io.resValid := resValid
  preDec.io.bpu      := bpuStage.bits
  preDec.io.inst     := respQ.io.deq.bits
  preDec.io.extFlush := extFlush

  val ibEntries = Wire(Vec(nWays, Valid(new IBEntryNew)))

  for (i <- 0 until nWays) {
    ibEntries(i).valid          := preDec.io.outValid(i)
    ibEntries(i).bits.addr      := preDec.io.outPc(i)
    ibEntries(i).bits.inst      := preDec.io.outInst(i)
    ibEntries(i).bits.inSlot    := preDec.io.outInSlot(i)
    ibEntries(i).bits.predictBT := preDec.io.outTarget(i)
    ibEntries(i).bits.tlblRfl   := respQ.io.deq.bits.except.refill
    ibEntries(i).bits.tlblInv   := respQ.io.deq.bits.except.invalid
  }

  // preDecs -> ib
  for (i <- 0 until nWays) {
    ib.io.write(i).valid := ibEntries(i).valid
    ib.io.write(i).bits := ibEntries(i).bits
  }

  for (i <- 0 until fetchGroupSize) {
    io.withDecode.ibufferEntries(i) <> ib.io.read(i)
  }

  ib.io.flush := extFlush
  io.iCache.flush := needFlush

  io.pc := pc
}
