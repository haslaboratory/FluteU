package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.cache.DCacheWithLSU
import mboom.components.SinglePortQueue
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode._
import mboom.core.components._
import mboom.config.CPUConfig._
import mboom.core.backend.commit.ROBCompleteBundle
import mboom.mmu.TLBDataResp
import mboom.core.backend.lsu.component._
import mboom.cp0.ExceptionBundle

class LsuWBv2 extends Bundle {
  val rob       = new ROBCompleteBundle(robEntryNumWidth)

  val prf           = new RegFileWriteIO
  val busyTableSpec = Valid(UInt(phyRegAddrWidth.W))
  val busyTableFact = Valid(UInt(phyRegAddrWidth.W))
  val busyTableCorr = Valid(UInt(phyRegAddrWidth.W))
}
class MemReqv2 extends Bundle {

  /**
    * If load, [[data]] is retrieved from sb with mask [[valid]]
    */
  val brMask       = UInt((nBranchCount+1).W)
  val origInData   = UInt(32.W)
  val immeData     = UInt(32.W)
  val cacheData    = UInt(32.W)
  val addr         = UInt(32.W)
  val loadMode     = UInt(LoadMode.width.W)
  val storeMode    = UInt(StoreMode.width.W)
  val valid        = Vec(4, Bool())
  val unCache      = Bool()
  val robAddr      = UInt(robEntryNumWidth.W)
  val writeRegAddr = UInt(phyRegAddrWidth.W)
  val writeEnable  = Bool()

  val tlblRfl      = Bool()
  val tlblInv      = Bool()
  val tlbsRfl      = Bool()
  val tlbsInv      = Bool()
  val tlbsMod      = Bool()
}

class LsuPipelinev3 extends Module {
  val io = IO(new Bundle {
    val uop         = Flipped(DecoupledIO(new MicroLSUOp))
    val prfRead     = Flipped(new RegFileReadIO)
    val prfReadExt  = Flipped(new RegFileExtReadIO)

    val dcache      = Flipped(new DCacheWithLSU)
    val tlbDataReq  = Output(UInt(20.W))
    val tlbDataResp = Flipped(new TLBDataResp)

    val potentialStallReg = Output(UInt(phyRegAddrWidth.W))

    val wb          = Output(new LsuWBv2)

    val dRetire    = Input(Bool())
    val unRetire   = Input(Bool())

    val flush      = Input(new ExecFlush(nBranchCount))
  })
  val dBuffer  = Module(new Sbufferv3)
  val unBuffer = Module(new UncacheBuffer)

  // read
  val readIn = io.uop
  io.prfRead.r1Addr := readIn.bits.baseOp.rsAddr
  io.prfRead.r2Addr := readIn.bits.baseOp.rtAddr
  val (op1, op2) = (io.prfRead.r1Data, io.prfRead.r2Data)
  val read2Mem1 = WireInit(readIn.bits)
  read2Mem1.baseOp.op1.op := op1
  when(readIn.bits.storeMode =/= StoreMode.disable) {
    read2Mem1.baseOp.op2.op := op2
  }
  // for lwl and lwr, the rt register data will be used
  io.prfReadExt.extAddr := readIn.bits.baseOp.extAddr
  when(readIn.bits.loadMode === LoadMode.lwr || readIn.bits.loadMode === LoadMode.lwl) {
    read2Mem1.baseOp.opExt.op := io.prfReadExt.extData
  }
  // calculate virtual address
  val vAddr = Wire(UInt(32.W))
  vAddr := op1 + readIn.bits.vAddr
  read2Mem1.vAddr := vAddr
  // mem1 Stage
  // val mem1Stage       = Module(new StageRegv2(new MicroLSUOp))
  val mem1StageQueue = Module(new SinglePortQueue(new MicroLSUOp, 2, hasFlush = true))
  mem1StageQueue.io.flush.get := io.flush.extFlush

  mem1StageQueue.io.enq.bits  := read2Mem1
  mem1StageQueue.io.enq.valid := io.uop.valid
  io.uop.ready    := mem1StageQueue.io.enq.ready

  // mem1
  // tlb cache valid / data cache req
  val mem1Valid = mem1StageQueue.io.deq.valid
  val mem1In    = mem1StageQueue.io.deq.bits
  io.tlbDataReq := mem1In.vAddr(31, 12)

  val cacheAccessReq = mem1Valid && mem1In.storeMode === StoreMode.disable
  when (cacheAccessReq) {
    io.dcache.valid     := 1.B
    io.dcache.write     := 0.B
    io.dcache.inval     := mem1In.loadMode === LoadMode.disable
    io.dcache.addr      := mem1In.vAddr
    io.dcache.validMask := 0.U.asTypeOf(Vec(4, Bool()))
    io.dcache.writeData := 0.U
  } .otherwise {
    io.dcache.valid     := dBuffer.io.head.valid
    io.dcache.write     := 1.B
    io.dcache.inval     := 0.B
    io.dcache.addr      := Cat(dBuffer.io.head.bits.addr, 0.U(2.W))
    io.dcache.validMask := dBuffer.io.head.bits.valid
    io.dcache.writeData := dBuffer.io.head.bits.data
  }
  dBuffer.io.head.ready := !io.dcache.stall && !cacheAccessReq

  // why && !io.dcache.stall ?
  io.wb.busyTableSpec.valid := mem1Valid && mem1In.baseOp.regWriteEn && !io.dcache.stall
  io.wb.busyTableSpec.bits  := mem1In.baseOp.writeRegAddr

  // mem2 Stage
  val mem2Stage = Module(new StageRegv2(new MicroLSUOp))
  mem2Stage.io.flush := io.flush

  val mem1toMem2      = WireInit(mem1In)
  mem1toMem2.pfn     := io.tlbDataResp.pfn
  mem1toMem2.uncache := io.tlbDataResp.uncache
  mem1toMem2.tlbRfl  := io.tlbDataResp.except.refill
  mem1toMem2.tlbInv  := io.tlbDataResp.except.invalid
  mem1toMem2.tlbMod  := io.tlbDataResp.except.modified

  val accFire = mem1Valid && mem1In.storeMode === StoreMode.disable && !io.dcache.stall
  val stFire = mem1Valid  && mem1In.storeMode =/= StoreMode.disable && dBuffer.io.write.ready && unBuffer.io.write.ready

  mem2Stage.io.in.bits   := mem1toMem2
  mem2Stage.io.in.valid  := accFire || stFire
  mem1StageQueue.io.deq.ready := mem2Stage.io.in.ready && (accFire || stFire)
  // mem2
  // calculate data / push into store buffer / read buffer
  val mem2Valid = mem2Stage.io.out.valid
  val mem2In      = mem2Stage.io.out.bits

  val pAddr       = Cat(mem2In.pfn, mem2In.vAddr(11, 0))

  // sbuffer
  dBuffer.io.flush             := io.flush
  dBuffer.io.read.memGroupAddr := pAddr(31, 2)
  dBuffer.io.write.memAddr     := pAddr
  dBuffer.io.write.memData     := mem2In.baseOp.op2.op
  dBuffer.io.write.valid       :=
    mem2Valid &&
      mem2In.storeMode =/= StoreMode.disable &&
      !mem2In.uncache &&
      !io.dcache.stall

  dBuffer.io.write.storeMode   := mem2In.storeMode
  dBuffer.io.write.unCache     := mem2In.uncache
  dBuffer.io.write.brMask      := mem2In.baseOp.brMask
  dBuffer.io.retire            := io.dRetire
  // wbbuffer
  unBuffer.io.flush             := io.flush
  unBuffer.io.write.memAddr     := pAddr
  unBuffer.io.write.memData     := mem2In.baseOp.op2.op
  unBuffer.io.write.valid       :=
    mem2Valid &&
      mem2In.storeMode =/= StoreMode.disable &&
      mem2In.uncache &&
      !io.dcache.stall
  unBuffer.io.write.storeMode   := mem2In.storeMode
  unBuffer.io.write.unCache     := mem2In.uncache
  unBuffer.io.write.brMask      := mem2In.baseOp.brMask
  unBuffer.io.retire            := io.unRetire

  // wb Stage
  val wbStage = Module(new StageReg(Valid(new MemReqv2)))
  val memReq = WireInit(0.U.asTypeOf(new MemReqv2))
  memReq.origInData   := mem2In.baseOp.opExt.op
  memReq.immeData     := dBuffer.io.read.data

  memReq.cacheData    := io.dcache.resp
  memReq.addr         := mem2In.vAddr
  memReq.loadMode     := mem2In.loadMode
  memReq.storeMode    := mem2In.storeMode
  memReq.robAddr      := mem2In.baseOp.robAddr
  memReq.writeRegAddr := mem2In.baseOp.writeRegAddr
  memReq.writeEnable  := mem2In.baseOp.regWriteEn
  memReq.tlblRfl      := mem2In.tlbRfl && mem2In.loadMode =/= LoadMode.disable
  memReq.tlblInv      := mem2In.tlbInv && mem2In.loadMode =/= LoadMode.disable
  memReq.tlbsRfl      := mem2In.tlbRfl && mem2In.storeMode =/= StoreMode.disable
  memReq.tlbsInv      := mem2In.tlbInv && mem2In.storeMode =/= StoreMode.disable
  memReq.tlbsMod      := mem2In.tlbMod && mem2In.storeMode =/= StoreMode.disable
  memReq.valid        := dBuffer.io.read.valid
  memReq.unCache      := mem2In.uncache
  memReq.brMask       := mem2In.baseOp.brMask

  wbStage.io.valid    := 1.B
  wbStage.io.flush    := 0.B
  wbStage.io.in.valid := mem2Valid && !io.dcache.stall
  wbStage.io.in.bits  := memReq
  mem2Stage.io.out.ready := !io.dcache.stall

  // wb
  val wbValid = wbStage.io.data.valid
  val wbIn      = wbStage.io.data.bits

  val replacedData = VecInit(
    for (i <- 0 until 4) yield {
      Mux(
        wbIn.valid(i),
        wbIn.immeData(i * 8 + 7, i * 8),
        wbIn.cacheData(i * 8 + 7, i * 8)
      )
    }
  )
  val orginData = VecInit(
    for (i <- 0 until 4) yield {
      wbIn.origInData(i * 8 + 7, i * 8)
    }
  )
  val finalData =
    LSUUtils.getLoadData(
      orginData,
      wbIn.loadMode,
      wbIn.addr(1, 0),
      replacedData
    )

  val writeRob  = WireInit(0.U.asTypeOf(new ROBCompleteBundle(robEntryNumWidth)))
  writeRob.valid     := wbValid
  writeRob.robAddr   := wbIn.robAddr
  writeRob.unCache   := wbIn.unCache
  writeRob.exception := 0.U.asTypeOf(new ExceptionBundle)
  writeRob.badvaddr  := wbIn.addr
  writeRob.exception.adES := (
    (wbIn.storeMode === StoreMode.word     && wbIn.addr(1, 0) =/= 0.U) ||
    (wbIn.storeMode === StoreMode.halfword && wbIn.addr(0) =/= 0.U)
  )
  writeRob.exception.adELd := (
    (wbIn.loadMode === LoadMode.word  && wbIn.addr(1, 0) =/= 0.U) ||
    (wbIn.loadMode === LoadMode.halfS && wbIn.addr(0) =/= 0.U) ||
    (wbIn.loadMode === LoadMode.halfU && wbIn.addr(0) =/= 0.U)
  )
  writeRob.exception.tlblRfl := wbIn.tlblRfl
  writeRob.exception.tlblInv := wbIn.tlblInv
  writeRob.exception.tlbsRfl := wbIn.tlbsRfl
  writeRob.exception.tlbsInv := wbIn.tlbsInv
  writeRob.exception.tlbsMod := wbIn.tlbsMod

  io.wb.rob := writeRob

  // prf
  io.wb.prf.writeEnable := wbValid && wbIn.writeEnable
  io.wb.prf.writeAddr   := wbIn.writeRegAddr
  io.wb.prf.writeData   := finalData

  // busyTable
  io.wb.busyTableCorr.valid := mem2Valid && mem2In.baseOp.regWriteEn
  io.wb.busyTableCorr.bits  := mem2In.baseOp.writeRegAddr

  io.wb.busyTableFact.valid := wbValid && wbIn.writeEnable
  io.wb.busyTableFact.bits  := wbIn.writeRegAddr

  io.dcache.unCacheWrite.valid     := unBuffer.io.head.valid
  io.dcache.unCacheWrite.bits.addr := Cat(unBuffer.io.head.bits.addr, 0.U(2.W))
  io.dcache.unCacheWrite.bits.writeData := unBuffer.io.head.bits.data
  io.dcache.unCacheWrite.bits.validMask := unBuffer.io.head.bits.valid
  unBuffer.io.head.ready := io.dcache.unCacheWrite.ready

  io.dcache.unCacheNum := unBuffer.io.num

  io.potentialStallReg := mem2In.baseOp.writeRegAddr
}
