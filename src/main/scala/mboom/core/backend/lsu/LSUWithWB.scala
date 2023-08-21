package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.cache.{DCacheReq, DCacheResp, DCacheRespv2, InformMeta}
import mboom.core.backend.decode.{LoadMode, MicroLSUOp, StoreMode}
import mboom.util.ValidBundle
import mboom.core.components.StageRegv2
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.lsu.component.{Sbuffer, SbufferEntry, WriteBackBuffer}

class LSUWithWB extends Module {
  val io = IO(new Bundle {
    val dcache        = new LSUWithDataCacheIO
    val uncache       = new LSUWithDataCacheIO
    val instr         = Flipped(DecoupledIO(new MicroLSUOp))
    val wbHeadEntry   = Decoupled(new SbufferEntry)

    val stRob         = ValidIO(new MemReq)
    val ldRobDc       = ValidIO(new MemReq)
    val ldRobUc       = ValidIO(new MemReq)

    val busyTable     = Vec(2, Valid(UInt(phyRegAddrWidth.W)))

    val flush         = Input(new ExecFlush(nBranchCount))

    val sbRetire      = Input(Bool())
    val sbKilled      = Input(Bool())
    val sbRetireReady = Output(Bool())
  })

  val sbuffer  = Module(new Sbuffer)
  val wbbuffer = Module(new WriteBackBuffer)
  val s0       = Module(new StageRegv2(new MicroLSUOp))
//  val dCacheQ  = Module(new Queue(new MemReq, 4, hasFlush = true))
//  val unCacheQ = Module(new Queue(new MemReq, 4, hasFlush = true))
//  val unCacheRespQ = Module(new Queue(new DCacheRespv2, 4, hasFlush = true))

  val memAddr    = Cat(s0.io.out.bits.pfn, s0.io.out.bits.vAddr(11, 0))

  val reqUncache = s0.io.out.bits.uncache

  val storeNum = sbuffer.io.sNum + wbbuffer.io.sNum
  val barrier  = reqUncache && s0.io.out.bits.loadMode =/= LoadMode.disable && storeNum =/= 0.U

  // sbuffer
  sbuffer.io.flush             := io.flush
  sbuffer.io.read.memGroupAddr := memAddr(31, 2)
  sbuffer.io.write.memAddr     := memAddr
  sbuffer.io.write.memData     := s0.io.out.bits.baseOp.op2.op
  sbuffer.io.write.valid       := s0.io.out.valid && s0.io.out.bits.storeMode =/= StoreMode.disable
  sbuffer.io.write.storeMode   := s0.io.out.bits.storeMode
  sbuffer.io.write.unCache     := reqUncache
  sbuffer.io.write.brMask      := s0.io.out.bits.baseOp.brMask
  sbuffer.io.retire            := io.sbRetire
  // wbbuffer
  wbbuffer.io.read.memGroupAddr := memAddr(31, 2)
  wbbuffer.io.write.valid       := io.sbRetire && sbuffer.io.headEntry.valid && !io.sbKilled
  wbbuffer.io.write.bits        := sbuffer.io.headEntry.bits
  io.sbRetireReady              := wbbuffer.io.write.ready

  io.wbHeadEntry <> wbbuffer.io.headEntry


  // TODO: no need to request when it is found in sbuffer / writebackbuffer
  val sbufferReadValid = sbuffer.io.read.valid

  val bufferReadValid = VecInit(
    for (i <- 0 until 4) yield {
      Mux(sbufferReadValid(i), sbuffer.io.read.valid(i), wbbuffer.io.read.valid(i))
    }
  )
  val bufferReadData = Cat(
    for (i <- (0 until 4).reverse) yield {
      Mux(sbufferReadValid(i), sbuffer.io.read.data(8*i+7, 8*i), wbbuffer.io.read.data(8*i+7, 8*i))
    }
  )


  val memReq = WireInit(0.U.asTypeOf(new MemReq))
  memReq.data := Mux(
    s0.io.out.bits.loadMode =/= LoadMode.disable,
    bufferReadData,
    s0.io.out.bits.baseOp.op2.op
  )
  memReq.addr         := s0.io.out.bits.vAddr
  memReq.mode         := Mux(s0.io.out.bits.storeMode =/= StoreMode.disable, s0.io.out.bits.storeMode, s0.io.out.bits.loadMode)
  memReq.robAddr      := s0.io.out.bits.baseOp.robAddr
  memReq.writeRegAddr := s0.io.out.bits.baseOp.writeRegAddr
  memReq.writeEnable  := s0.io.out.bits.baseOp.regWriteEn
  memReq.tlblRfl      := s0.io.out.bits.tlbRfl && s0.io.out.bits.loadMode =/= LoadMode.disable
  memReq.tlblInv      := s0.io.out.bits.tlbInv && s0.io.out.bits.loadMode =/= LoadMode.disable
  memReq.tlbsRfl      := s0.io.out.bits.tlbRfl && s0.io.out.bits.storeMode =/= StoreMode.disable
  memReq.tlbsInv      := s0.io.out.bits.tlbInv && s0.io.out.bits.storeMode =/= StoreMode.disable
  memReq.tlbsMod      := s0.io.out.bits.tlbMod && s0.io.out.bits.storeMode =/= StoreMode.disable
  memReq.valid        := bufferReadValid
  memReq.origInData   := s0.io.out.bits.baseOp.opExt.op
  memReq.brMask       := s0.io.out.bits.baseOp.brMask

  val tlbMiss = s0.io.out.bits.tlbRfl || s0.io.out.bits.tlbInv || s0.io.out.bits.tlbMod
  val quickReturn = tlbMiss
  // store / tlb exception
  val stValid    = s0.io.out.valid &&
    ((s0.io.out.bits.storeMode =/= StoreMode.disable && sbuffer.io.write.ready) || quickReturn)
  val stRobValid = RegNext(stValid, 0.B)
  val stRobBits  = RegNext(memReq, 0.U.asTypeOf(new MemReq))

  io.stRob.valid := stRobValid
  io.stRob.bits  := stRobBits

  // load
  val dcacheReqReady = io.dcache.req.ready
  val uncacheReqReady = io.uncache.req.ready

  val dcacheReqValid =
    s0.io.out.valid && !reqUncache && (s0.io.out.bits.loadMode =/= LoadMode.disable) && !quickReturn
  val uncacheReqValid =
    s0.io.out.valid && reqUncache && (s0.io.out.bits.loadMode =/= LoadMode.disable)  && !barrier && !quickReturn
  val dcacheQValid = s0.io.out.valid && s0.io.out.bits.loadMode =/= LoadMode.disable &&
    !reqUncache && dcacheReqReady && !quickReturn
  val uncacheQValid = s0.io.out.valid && s0.io.out.bits.loadMode =/= LoadMode.disable &&
    reqUncache && uncacheReqReady && !barrier && !quickReturn

  io.dcache.req.valid          := dcacheReqValid
  io.dcache.req.bits.validMask := 0.U.asTypeOf(Vec(4, Bool()))
  io.dcache.req.bits.addr      := Cat(memAddr(31, 2), 0.U(2.W))
  io.dcache.req.bits.writeData := 0.U
  io.dcache.req.bits.meta      := memReq

  io.uncache.req.valid          := uncacheReqValid
  io.uncache.req.bits.validMask := 0.U.asTypeOf(Vec(4, Bool()))
  io.uncache.req.bits.addr      := memAddr // important for uart !!! TODO: fix read size
  io.uncache.req.bits.writeData := 0.U
  io.uncache.req.bits.meta      := memReq


  val reqFires = io.dcache.req.fire || io.uncache.req.fire || stValid

  s0.io.out.ready := reqFires

  io.instr.ready := s0.io.in.ready
  s0.io.in.bits := io.instr.bits
  s0.io.in.valid := io.instr.valid

  s0.io.flush := io.flush

  // read resp
  // dcache
  val dcacheResp   = RegInit(0.U.asTypeOf(Valid(new DCacheRespv2)))

  // potential bug!
  dcacheResp.valid := io.dcache.resp.valid//  && !io.flush
  dcacheResp.bits  := io.dcache.resp.bits


  val dcacheReplacedData = VecInit(
    for (i <- 0 until 4) yield {
      Mux(
        dcacheResp.bits.meta.valid(i),
        dcacheResp.bits.meta.data(i * 8 + 7, i * 8),
        dcacheResp.bits.loadData(i * 8 + 7, i * 8)
      )
    }
  )
  val dcacheOrginData  = VecInit(
    for (i <- 0 until 4) yield {
      dcacheResp.bits.meta.origInData(i*8+7, i*8)
    }
  )
  val dcacheFinalData =
    LSUUtils.getLoadData(
      dcacheOrginData,
      dcacheResp.bits.meta.mode,
      dcacheResp.bits.meta.addr(1, 0),
      dcacheReplacedData
    )

  val dcacheRespValid = dcacheResp.valid

  val ldRobDc = WireInit(0.U.asTypeOf(new MemReq))
  ldRobDc.addr         := dcacheResp.bits.meta.addr
  ldRobDc.mode         := dcacheResp.bits.meta.mode
  ldRobDc.robAddr      := dcacheResp.bits.meta.robAddr
  ldRobDc.writeRegAddr := dcacheResp.bits.meta.writeRegAddr
  ldRobDc.writeEnable  := dcacheResp.bits.meta.writeEnable
  ldRobDc.valid        := dcacheResp.bits.meta.valid
  ldRobDc.data         := dcacheFinalData

  io.busyTable(0).valid := io.dcache.inform.valid && io.dcache.inform.bits.writeEnable
  io.busyTable(0).bits  := io.dcache.inform.bits.writeRegAddr

  io.ldRobDc.valid  := dcacheRespValid
  io.ldRobDc.bits   := ldRobDc

  // uncache

  val uncacheResp = RegInit(0.U.asTypeOf(Valid(new DCacheRespv2)))

  uncacheResp.valid := io.uncache.resp.valid//  && !io.flush
  uncacheResp.bits  := io.uncache.resp.bits

  val uncacheOrginData = VecInit(
    for (i <- 0 until 4) yield {
      uncacheResp.bits.meta.origInData(i * 8 + 7, i * 8)
    }
  )
  val uncacheReplacedData = VecInit(
    for (i <- 0 until 4) yield uncacheResp.bits.loadData(i * 8 + 7, i * 8)
  )

  val uncacheFinalData =
    LSUUtils.getLoadData(
      uncacheOrginData,
      uncacheResp.bits.meta.mode,
      uncacheResp.bits.meta.addr(1, 0),
      uncacheReplacedData
    )

  val uncacheRespValid = uncacheResp.valid


  val ldRobUc = WireInit(0.U.asTypeOf(new MemReq))
  ldRobUc.addr         := uncacheResp.bits.meta.addr
  ldRobUc.mode         := uncacheResp.bits.meta.mode
  ldRobUc.robAddr      := uncacheResp.bits.meta.robAddr
  ldRobUc.writeRegAddr := uncacheResp.bits.meta.writeRegAddr
  ldRobUc.writeEnable  := uncacheResp.bits.meta.writeEnable
  ldRobUc.valid        := uncacheResp.bits.meta.valid
  ldRobUc.data         := uncacheFinalData

  io.busyTable(1).valid := uncacheRespValid && uncacheResp.bits.meta.writeEnable
  io.busyTable(1).bits  := uncacheResp.bits.meta.writeRegAddr

  io.ldRobUc.valid := uncacheRespValid
  io.ldRobUc.bits  := ldRobUc
}
