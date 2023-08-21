package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.core.components.{RegFileExtReadIO, RegFileReadIO, RegFileWriteIO, StageRegv2}
import mboom.core.backend.alu.AluWB
import mboom.core.backend.decode.{LoadMode, MicroLSUOp, MicroOp, StoreMode}
import mboom.core.backend.commit.ROBCompleteBundle
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.lsu.component.SbufferEntry
import mboom.cp0.ExceptionBundle
import mboom.mmu.{TLBDataEx, TLBDataResp}


class LsuWB extends Bundle {
  val rob = Vec(3, new ROBCompleteBundle(robEntryNumWidth))

  val prf       = Vec(2, new RegFileWriteIO)
  val busyTable = Vec(3, Valid(UInt(phyRegAddrWidth.W)))
}

//class DTLBReq extends Bundle {
//  val vpn   = UInt(20.W)
//  val write = Bool()
//}
//
//class DTLBResp extends Bundle {
//  val pfn     = Output(UInt(20.W))
//  val uncache = Output(Bool())
//  val dataEx  = Output(new TLBDataEx)
//}
//class DTLB extends Module {
//  val io = IO(new Bundle {
//    val req       = Flipped(Valid(new DTLBReq))
//    val resp      = Decoupled(new DTLBResp)
//    // L2 TLB req
//    val tlbDataReq  = Output(UInt(20.W))
//    val tlbDataResp = Flipped(new TLBDataResp)
//
//    // 当 cp0 出现上下文切换时需要进行 fence
//    val fence     = Input(Bool())
//    val flush     = Input(Bool())
//  })
//
//  val idle :: refilling :: informing :: Nil = Enum(3)
//  val state = RegInit(idle)
//
//  // L1 TLB
//  val vpn      = RegInit(0.U(20.W))
//  val pfn      = RegInit(0.U(20.W))
//  val uncache  = RegInit(0.B)
//  val dirty    = RegInit(0.B)
//  val valid    = RegInit(0.B)
//  // temp data
//  val refillReq = RegInit(0.U(20.W))
//  val dataEx    = RegInit(0.U.asTypeOf(new TLBDataEx))
//
//  // state === idle
//  val mapped = (!io.req.bits.vpn(19)) || (io.req.bits.vpn(19, 18) === "b11".U(32.W))
//  val hit    = (io.req.bits.vpn === vpn) && valid && (!io.req.bits.write || dirty)
//  // state === refilling
//  io.tlbDataReq := refillReq
//
//  when(io.fence) {
//    valid := 0.B
//  } .elsewhen(state === refilling) {
//    vpn     := refillReq
//    pfn     := io.tlbDataResp.pfn
//    uncache := io.tlbDataResp.uncache
//    dirty   := io.tlbDataResp.dirty
//    valid   := io.tlbDataResp.valid
//    dataEx  := io.tlbDataResp.except
//  }
//
//  // reforming
//  io.resp.valid        := ((!mapped || hit) && (state === idle)) || (state === informing)
//  io.resp.bits.pfn     := Mux(mapped, pfn, Cat(0.U(3.W), io.req.bits.vpn(16, 0)))
//  io.resp.bits.uncache := (io.req.bits.vpn(19, 17) === "b101".U(3.W)) || (mapped && uncache)
//  io.resp.bits.dataEx  := dataEx
//
//  switch (state) {
//    is (idle) {
//      when (io.req.valid && mapped && !hit) {
//        state     := refilling
//        refillReq := io.req.bits.vpn
//      }
//    }
//    is (refilling) {
//      state := informing
//    }
//    is (informing) {
//      when (io.resp.ready) {
//        state  := idle
//        dataEx := 0.U.asTypeOf(new TLBDataEx)
//      }
//    }
//  }
//  when (io.flush) {
//    state := idle
//  }
//}
class LsuPipeline extends Module {
  val io = IO(new Bundle {
    val uop      = Flipped(DecoupledIO(new MicroLSUOp))
    val prfRead  = Flipped(new RegFileReadIO)
    val prfReadExt = Flipped(new RegFileExtReadIO)
    val wb       = Output(new LsuWB)
    val dcache   = new LSUWithDataCacheIO
    val uncache  = new LSUWithDataCacheIO
    val sbRetire = Input(Bool())
    val sbKilled = Input(Bool())
    val flush    = Input(new ExecFlush(nBranchCount))
    val sbRetireReady = Output(Bool())

    val wbHeadEntry = Decoupled(new SbufferEntry)

    val tlbDataReq  = Output(UInt(20.W))
    val tlbDataResp = Flipped(new TLBDataResp)
  })

  val readIn = io.uop
  io.prfRead.r1Addr := readIn.bits.baseOp.rsAddr
  io.prfRead.r2Addr := readIn.bits.baseOp.rtAddr
  val (op1, op2) = (io.prfRead.r1Data, io.prfRead.r2Data)
  val read2Tlb    = WireInit(readIn.bits)
  read2Tlb.baseOp.op1.op := op1
  when(readIn.bits.storeMode =/= StoreMode.disable) {
    read2Tlb.baseOp.op2.op := op2
  }
  // for lwl and lwr, the rt register data will be used
  io.prfReadExt.extAddr := readIn.bits.baseOp.extAddr
  when (readIn.bits.loadMode === LoadMode.lwr || readIn.bits.loadMode === LoadMode.lwl) {
    read2Tlb.baseOp.opExt.op := io.prfReadExt.extData
  }

  val vAddr = Wire(UInt(32.W))
  vAddr := op1 + readIn.bits.vAddr
  read2Tlb.vAddr := vAddr
  // tlb Stage
  val tlbStage      = Module(new StageRegv2(new MicroLSUOp))
  tlbStage.io.flush := io.flush

  tlbStage.io.in.bits  := read2Tlb
  tlbStage.io.in.valid := io.uop.valid
  io.uop.ready         := tlbStage.io.in.ready

  io.tlbDataReq       := tlbStage.io.out.bits.vAddr(31, 12)

  val tlb2Ex = WireInit(tlbStage.io.out.bits)
  tlb2Ex.pfn     := io.tlbDataResp.pfn
  tlb2Ex.uncache := io.tlbDataResp.uncache
  tlb2Ex.tlbRfl  := io.tlbDataResp.except.refill
  tlb2Ex.tlbInv  := io.tlbDataResp.except.invalid
  tlb2Ex.tlbMod  := io.tlbDataResp.except.modified && tlbStage.io.out.bits.storeMode =/= 0.U

  val lsu = Module(new LSUWithWB)
  lsu.io.dcache <> io.dcache
  lsu.io.uncache <> io.uncache
  lsu.io.flush    := io.flush
  lsu.io.sbRetire := io.sbRetire
  lsu.io.sbKilled := io.sbKilled

  lsu.io.instr.valid := tlbStage.io.out.valid
  lsu.io.instr.bits  := tlb2Ex
  tlbStage.io.out.ready := lsu.io.instr.ready

  val writeRob   = WireInit(0.U.asTypeOf(Vec(3, new ROBCompleteBundle(robEntryNumWidth))))
  val stMemReq   = lsu.io.stRob.bits
  val ldMemReqDc = lsu.io.ldRobDc.bits
  val ldMemReqUc = lsu.io.ldRobUc.bits

  // val prfWriteEnable = lsu.io.toRob.valid && lsuMemReq.loadMode =/= LoadMode.disable && lsuMemReq.writeEnable

  // wb.rob
  writeRob(0).valid     := lsu.io.stRob.valid
  writeRob(0).robAddr   := stMemReq.robAddr
  writeRob(0).exception := 0.U.asTypeOf(new ExceptionBundle)
  writeRob(0).badvaddr  := stMemReq.addr
  writeRob(0).exception.adES := (
    (stMemReq.mode === StoreMode.word     && stMemReq.addr(1, 0) =/= 0.U) ||
    (stMemReq.mode === StoreMode.halfword && stMemReq.addr(0) =/= 0.U)
  )
  writeRob(0).exception.tlblRfl := stMemReq.tlblRfl
  writeRob(0).exception.tlblInv := stMemReq.tlblInv
  writeRob(0).exception.tlbsRfl := stMemReq.tlbsRfl
  writeRob(0).exception.tlbsInv := stMemReq.tlbsInv
  writeRob(0).exception.tlbsMod := stMemReq.tlbsMod
  writeRob(1).valid     := lsu.io.ldRobDc.valid
  writeRob(1).robAddr   := ldMemReqDc.robAddr
  writeRob(1).exception := 0.U.asTypeOf(new ExceptionBundle)
  writeRob(1).badvaddr  := ldMemReqDc.addr
  writeRob(1).exception.adELd := (
    (ldMemReqDc.mode === LoadMode.word  && ldMemReqDc.addr(1, 0) =/= 0.U) ||
    (ldMemReqDc.mode === LoadMode.halfS && ldMemReqDc.addr(0) =/= 0.U) ||
    (ldMemReqDc.mode === LoadMode.halfU && ldMemReqDc.addr(0) =/= 0.U)
  )
  writeRob(2).valid     := lsu.io.ldRobUc.valid
  writeRob(2).robAddr   := ldMemReqUc.robAddr
  writeRob(2).exception := 0.U.asTypeOf(new ExceptionBundle)
  writeRob(2).badvaddr  := ldMemReqUc.addr
  writeRob(2).exception.adELd := (
    (ldMemReqUc.mode === LoadMode.word  && ldMemReqUc.addr(1, 0) =/= 0.U) ||
    (ldMemReqUc.mode === LoadMode.halfS && ldMemReqUc.addr(0) =/= 0.U) ||
    (ldMemReqUc.mode === LoadMode.halfU && ldMemReqUc.addr(0) =/= 0.U)
  )
  io.wb.rob         := writeRob

  // wb.busyTable
  io.wb.busyTable(0)  := lsu.io.busyTable(0)
  io.wb.busyTable(1)  := lsu.io.busyTable(1)

  // wb.prf
  io.wb.prf(0).writeEnable := lsu.io.ldRobDc.valid && ldMemReqDc.writeEnable
  io.wb.prf(0).writeAddr   := ldMemReqDc.writeRegAddr
  io.wb.prf(0).writeData   := ldMemReqDc.data
  io.wb.prf(1).writeEnable := lsu.io.ldRobUc.valid && ldMemReqUc.writeEnable
  io.wb.prf(1).writeAddr   := ldMemReqUc.writeRegAddr
  io.wb.prf(1).writeData   := ldMemReqUc.data

  // test
  io.wb.busyTable(2).valid := lsu.io.stRob.valid && stMemReq.writeEnable
  io.wb.busyTable(2).bits  := stMemReq.writeRegAddr

  // wbbuffer
  io.sbRetireReady := lsu.io.sbRetireReady
  io.wbHeadEntry   <> lsu.io.wbHeadEntry
}
