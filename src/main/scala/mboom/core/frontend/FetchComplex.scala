package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.cache.{ICacheResp, ICacheResp1, ICacheWithCore, ICacheWithCore1}
import mboom.components.SinglePortQueue
import mboom.config.CPUConfig._
import mboom.core.frontend.BPU._
import mboom.core.frontend.BPU.component.RASEntry
import mboom.mmu.{TLBInstrEx, TLBInstrResp}

class FetchIONew extends Bundle {
  val ibufferEntries = Vec(decodeWay, Decoupled(new IBEntryNew))
}
class IBEntryNew extends Bundle {
  val inst      = UInt(instrWidth.W) // 指令
  val addr      = UInt(addrWidth.W)  // 指令地址
  val inSlot    = Bool()             // 为延迟槽，used in cp0
  val predictBT = UInt(addrWidth.W) // 预测的分支地址
  val tlblRfl   = Bool()
  val tlblInv   = Bool()
}

class FetchWithCP0 extends Bundle {
  val intrReq = Bool()       // 需要取指令
  val intrAddr = UInt(dataWidth.W)
  val eretReq = Bool()       // 需要返回
  val epc     = UInt(dataWidth.W) // 异常返回地址寄存器
}

class FetchIOWithBPU extends Bundle {
  val ibufferEntries = Vec(decodeWay, Decoupled(new IbEntryWithBPU))
}

class IbEntryWithBPU extends Bundle {
  val inst      = UInt(instrWidth.W)
  val addr      = UInt(addrWidth.W)
  val inSlot    = Bool()
  val predictBT = UInt(addrWidth.W) // frontend

  val ras_sp    = UInt(5.W)
  val ras_entry = new RASEntry
}

class BranchTrainWithBPU extends Bundle {
  val pc       = UInt(addrWidth.W)
  val isBranch = Bool()
  val taken    = Bool()
  val br_type  = UInt(BranchType.width.W)
  val bta      = UInt(addrWidth.W)
  val flush    = Bool()
}

class BranchRestoreWithBPU extends Bundle {
  val address   = Valid(UInt(addrWidth.W))
  // ras_sp / ras_entry = the newest
  // val ras_sp    = UInt(5.W)
  // val ras_entry = new RASEntry
}

class BranchCommitWithBPU extends Bundle {
  val train = Vec(fetchGroupSize, Valid(new BranchTrainWithBPU))
  val restore = new BranchRestoreWithBPU
}

class PcQueueEntryWithCache extends Bundle {
  val pc        = UInt(addrWidth.W)

  val taken     = Vec(fetchGroupSize, Bool())
  val bta       = Vec(fetchGroupSize, UInt(addrWidth.W))
  // may influence state in predecode module
  val delaySlot = Bool()
}

class FetchComplex extends Module {
  assert(fetchGroupSize == 2)

  private val pcQueueVolume: Int = 4

  val io = IO(new Bundle {
    val withDecode   = new FetchIONew()
    val branchCommit = Input(new BranchCommitWithBPU)
    val iCache       = Flipped(new ICacheWithCore1)
    val cp0          = Input(new FetchWithCP0)
    val pc           = Output(UInt(addrWidth.W))
  })

  val bpu       = Module(new BPUThreeStage(BPUParam(fetchGroupSize)))
  val ib        = Module(new Ibuffer(new IBEntryNew, ibufferAmount, decodeWay, fetchGroupSize + 1))
  val pcQ       = Module(new SinglePortQueue(new PcQueueEntryWithCache, pcQueueVolume, hasFlush = true))
  val respStage = RegInit(0.U.asTypeOf(Valid(new ICacheResp1)))
  val preDec    = Module(new PreDecodeWithCache)

  val branchCommitRestore = io.branchCommit.restore.address
  val cp0Req              = io.cp0

  // flush signals in frontend is independent && stall for one cycle
  val intrEretReq         = cp0Req.intrReq || cp0Req.eretReq
  val extFlush            = branchCommitRestore.valid || intrEretReq
  val innerFlush          = preDec.io.out.innerFlush
  val needFlush           = extFlush || innerFlush
  val ibRoom              = ib.io.space
  val ibPermitCache       = ibRoom > 8.U
  val cacheFree           = io.iCache.req.ready
  val pcQEnqReady         = pcQ.io.enq.ready

  val setup = RegInit(0.B)
  when(!setup) {
    setup := 1.B
  }
  val pc = RegInit("hbfc00000".U(32.W))
  val linearNpc = MuxCase(pc+8.U, Seq(
    !setup                     -> pc,
    iCacheConfig.firstLast(pc) -> (pc+4.U),
  ))
  val predictJump = bpu.io.request.outValid && bpu.io.request.taken.reduce(_ || _)
  val predictNpc  = Mux(bpu.io.request.taken(0), bpu.io.request.bta(0), bpu.io.request.bta(1))

  // control signals
  val pcValid       = setup && (!predictJump || bpu.io.request.delaySlot)
  val bpuOutValid   = bpu.io.request.outValid

  val cacheBlocked  = pcValid && (!cacheFree || !ibPermitCache)

  val cacheReqValid = pcValid && ibPermitCache
  val pcQEnqValid   = bpuOutValid && !cacheBlocked
  val pcRenewal     = !cacheBlocked

  // IF0 PCgen && BPU
  val npc = Wire(UInt(32.W))
  when(cp0Req.intrReq) {
    npc := cp0Req.intrAddr
  }.elsewhen(cp0Req.eretReq) {
    npc := cp0Req.epc
  }.elsewhen(branchCommitRestore.valid) {
    // printf(p"reset to: ${Hexadecimal(branchCommitRestore.bits)}\n")
    npc := branchCommitRestore.bits
  }.elsewhen(innerFlush) {
    npc := preDec.io.out.bta
  }.otherwise {
    npc := Mux(predictJump, predictNpc, linearNpc)
  }

  bpu.io.request.stall := !pcRenewal && !needFlush
  bpu.io.request.pc    := npc
  val branchTrain = RegNext(io.branchCommit.train, 0.U.asTypeOf(Vec(fetchGroupSize, Valid(new BranchTrainWithBPU))))

  for (i <- 0 until fetchGroupSize) yield {
    bpu.io.commit(i).valid         := branchTrain(i).valid
    bpu.io.commit(i).bits.pc       := branchTrain(i).bits.pc
    bpu.io.commit(i).bits.isBranch := branchTrain(i).bits.isBranch
    bpu.io.commit(i).bits.taken    := branchTrain(i).bits.taken
    bpu.io.commit(i).bits.br_type  := branchTrain(i).bits.br_type
    bpu.io.commit(i).bits.bta      := branchTrain(i).bits.bta
    bpu.io.commit(i).bits.flush    := branchTrain(i).bits.flush
  }
  bpu.io.flush.innerFlush := innerFlush
  bpu.io.flush.extFlush := extFlush

  // IF1 iCache
  when (extFlush || innerFlush || pcRenewal) {
    pc := npc
  }

  io.iCache.req.valid     := cacheReqValid
  io.iCache.req.bits.addr := pc

  // IF2 pcQ
  pcQ.io.enq.valid          := pcQEnqValid
  pcQ.io.enq.bits.pc        := bpu.io.request.outPc
  pcQ.io.enq.bits.taken     := bpu.io.request.taken
  pcQ.io.enq.bits.bta       := bpu.io.request.bta
  pcQ.io.enq.bits.delaySlot := bpu.io.request.delaySlot
  pcQ.io.deq.ready          := respStage.valid
  pcQ.io.flush.get          := needFlush

  val insertIntoIb = pcQ.io.deq.fire
  // iCache -> respStage
  val resultValid = respStage.valid && pcQ.io.deq.valid && !innerFlush
  io.iCache.resp.ready := 1.B // resultValid || !respStage.valid

  val cacheRespValid = io.iCache.resp.valid
  when(needFlush) {
    respStage.valid := 0.B
    respStage.bits  := 0.U.asTypeOf(new ICacheResp1)
  }.elsewhen(resultValid || !respStage.valid) {
    respStage.valid := io.iCache.resp.fire
    respStage.bits := io.iCache.resp.bits
  }

  // respStage -> preDecs
  val pcQEntry = pcQ.io.deq.bits
  preDec.io.flush := extFlush
  preDec.io.resultValid := resultValid
  preDec.io.instruction := respStage
  preDec.io.pcEntry := pcQ.io.deq.bits

  val ibEntries = Wire(Vec(3, Valid(new IBEntryNew)))

  ibEntries(0).valid          := preDec.io.out.outValid(0)
  ibEntries(0).bits.addr      := pcQEntry.pc
  ibEntries(0).bits.inst      := respStage.bits.data(0)
  ibEntries(0).bits.inSlot    := preDec.io.out.inSlot(0)
  ibEntries(0).bits.predictBT := preDec.io.out.predictBT(0)
  ibEntries(0).bits.tlblRfl   := respStage.bits.except.refill
  ibEntries(0).bits.tlblInv   := respStage.bits.except.invalid
  ibEntries(1).valid          := preDec.io.out.outValid(1)
  ibEntries(1).bits.addr      := pcQEntry.pc + 4.U
  ibEntries(1).bits.inst      := respStage.bits.data(1)
  ibEntries(1).bits.inSlot    := preDec.io.out.inSlot(1)
  ibEntries(1).bits.predictBT := preDec.io.out.predictBT(1)
  ibEntries(1).bits.tlblRfl   := respStage.bits.except.refill
  ibEntries(1).bits.tlblInv   := respStage.bits.except.invalid
  ibEntries(2).valid          := preDec.io.out.outValid(2)
  ibEntries(2).bits.addr      := pcQEntry.pc + 8.U
  ibEntries(2).bits.inst      := respStage.bits.data(2)
  ibEntries(2).bits.inSlot    := preDec.io.out.inSlot(2)
  ibEntries(2).bits.predictBT := pcQEntry.pc + 16.U
  ibEntries(2).bits.tlblRfl   := respStage.bits.except.refill
  ibEntries(2).bits.tlblInv   := respStage.bits.except.invalid

  // preDecs -> ib
  for (i <- 0 to 2) {
    ib.io.write(i).valid := ibEntries(i).valid
    ib.io.write(i).bits := ibEntries(i).bits
  }

  for (i <- 0 to 1) {
    io.withDecode.ibufferEntries(i) <> ib.io.read(i)
  }

  ib.io.flush := extFlush
  io.iCache.flush := needFlush

  io.pc := pc

//  for (i <- 0 until 2) {
//    when(io.withDecode.ibufferEntries(i).fire) {
//      printf(p"fetch_pc:${Hexadecimal(io.withDecode.ibufferEntries(i).bits.addr)}, ")
//      printf(p"pred_pc:${Hexadecimal(io.withDecode.ibufferEntries(i).bits.predictBT)}, ")
//      printf(p"fetch_instr: ${Hexadecimal(io.withDecode.ibufferEntries(i).bits.inst)}\n")
//    }
//  }
}
