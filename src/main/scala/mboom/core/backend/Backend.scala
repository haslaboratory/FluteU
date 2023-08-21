package mboom.core.backend

import chisel3._
import chisel3.util._
import mboom.cache.{CacheInvalidate, DCacheReq1, DCacheReqv2, DCacheWithCore, DCacheWithCorev2, InformMeta}
import mboom.core.frontend._
import mboom.core.backend.decode._
import mboom.core.backend.rename._
import mboom.core.components.StageReg
import mboom.core.backend.utils.DecodeRenameQueue
import mboom.core.backend.utils.RenameDispatchQueue
import mboom.core.backend.dispatch._
import mboom.core.backend.commit.{Commit, ROB, ROBEntry, ROBFlush}
import mboom.core.backend.alu._
import mboom.core.components.RegFile
import mboom.config.CPUConfig._
import mboom.core.backend.bru.{BruIssue, BruIssueQueue, BruPipeline}
import mboom.core.backend.lsu._
import mboom.core.backend.utils._
import mboom.cp0.{CP0Read, CP0TLBReq, CP0WithCommit, CP0Write}
import mboom.core.backend.mdu.{MDUTop, MduIssue, MduIssueQueue}
import mboom.mmu.{TLBReq, TLBWithCore}
import mboom.mmu.TLBDataResp

class ExecFlush(nBrCount: Int) extends Bundle {
  val extFlush   = Bool()
  val brMissPred = Bool()
  val brMask     = UInt((nBrCount+1).W)
}

class Backend(nWays: Int = 2) extends Module {
//  require(nWays == 2)
//  val io = IO(new Bundle {
//    val ibuffer      = Vec(nWays, Flipped(DecoupledIO(new IBEntryNew)))
//    val dcache       = Flipped(new DCacheWithCorev2)
//    val uncache      = Flipped(new DCacheWithCorev2)
//    val branchCommit = Output(new BranchCommitWithBPU)
//    val cp0          = Flipped(new CP0WithCommit)
//    val cp0IntrReq   = Input(Bool())
//    val cp0EretReq   = Input(Bool())
//    val cp0Read      = Flipped(new CP0Read)
//    val cp0Write     = Output(new CP0Write)
//
//    val cp0TLBReq    = ValidIO(new CP0TLBReq)
//
//    val tlb          = Flipped(new TLBWithCore)
//
//    val icacheInv    = Flipped(new CacheInvalidate)
//    val dcacheInv    = Flipped(new CacheInvalidate)
//
//    // debug
//    val debug_rob_valid_0 = Output(Bool())
//    val debug_rob_pc_0    = Output(UInt(32.W))
//    val debug_rob_instr_0 = Output(UInt(32.W))
//    val debug_rob_valid_1 = Output(Bool())
//    val debug_rob_pc_1    = Output(UInt(32.W))
//    val debug_rob_instr_1 = Output(UInt(32.W))
//
//    val debug_ls_fire = Output(Bool())
//    val debug_ls_addr = Output(UInt(32.W))
//    val debug_valid_mask = Output(UInt(4.W))
//    val debug_store_data = Output(UInt(32.W))
//    val debug_resp_valid = Output(Bool())
//    val debug_load_data = Output(UInt(32.W))
//
//    val debug_exception = Output(UInt(11.W))
//    val debug_badvaddr  = Output(UInt(32.W))
//
//    val debug_dcache_state = Output(UInt(4.W))
//    val debug_out_num = Output(UInt(3.W))
//  })
//
//  val decoders = for (i <- 0 until nWays) yield Module(new Decoder)
//  val decodeSync = Module(new DecodeSync(nWays))
//
//  val rename    = Module(new Renamev2(nWays = nWays, nCommit = nWays, nBrCount = 4))
//  val busyTable = Module(new BusyTablev2(nRead = 24, nExtRead = 2, nCheckIn = 2, nCheckOut = 7, nBrCount = nBranchCount))
//  // val brBusyTable = Module(new BrBusyTable(nRead = 10,nBrCount = 4))
//  val regfile   = Module(new RegFile(numRead = 5, numWrite = 6, numExtRead = 3))
//
//  val dispatch = Module(new Dispatchv2)
//
//  // val branchUnit = Module(new BranchRestore(nBranchCount))
//
//  val rob = Module(
//    new ROB(numEntries = robEntryAmount, numRead = 2, numWrite = 2, numSetComplete = 7)
//  )
//  val commit    = Module(new Commit(nCommit = nWays))
//
//  val decodeRenameQ = Module(new DecodeRenameQueue())
//  // val renameStage = Module(new StageReg(Vec(nWays, Valid(new RenameOp))))
//  val renameDispatchQ = Module(new RenameDispatchQueue(nWays = nWays))
//
//  val extFlush = io.cp0IntrReq || io.cp0EretReq || commit.io.branch.restore.address.valid
//
//  for (i <- 0 until nWays) {
//    decoders(i).io.instr       := io.ibuffer(i).bits
//    decodeRenameQ.io.enq(i).bits  := decoders(i).io.microOp
//    decodeRenameQ.io.enq(i).valid := io.ibuffer(i).valid
//  }
//  decoders(0).io.syncIn := decodeSync.io.out
//  decoders(1).io.syncIn := decoders(0).io.syncOut
//  for (i <- 0 until nWays) {
//    decodeSync.io.in(i).valid := io.ibuffer(i).fire
//    decodeSync.io.in(i).bits  := decoders(i).io.syncOut
//  }
//
//  rename.io.decode     <> decodeRenameQ.io.deq
//  busyTable.io.checkIn := rename.io.checkIn
//  busyTable.io.brRequest := rename.io.brRequest
//  busyTable.io.brTag     := rename.io.brTag
//
//  renameDispatchQ.io.enq <> rename.io.dispatch
//  renameDispatchQ.io.robValid0 := rob.io.read(0).valid
//  renameDispatchQ.io.robValid1 := rob.io.read(1).valid
//
//  dispatch.io.in         <> renameDispatchQ.io.deq
//  rob.io.write           <> dispatch.io.rob
//
//  // stall
//  // val stall = rename.io.stallReq || dispatch.io.stallReq
//  for (i <- 0 until nWays) {
//    io.ibuffer(i).ready := decodeRenameQ.io.enq(i).ready
//  }
//  // decodeStage.io.valid := !stall
//  // renameStage.io.valid := !(dispatch.io.stallReq)
//
//  //----------------------------------commit------------------
//  commit.io.rob <> rob.io.read
//  rename.io.commit          := commit.io.commit
//  io.branchCommit.train     := commit.io.branch.train
//
//  io.cp0                    <> commit.io.cp0
//  commit.io.flush          := extFlush
//
//  //------------------------issue------------------------------------
//  private val aluDetectWidth = 8
//  private val lsuDetectWidth = 4
//  private val nAluPl      = 2 // number of alu piplines
//  private val aluQueryWidth = aluDetectWidth+2
//  private val lsuQueryWidth = lsuDetectWidth+2
//  private val mduQueryWidth = 1+2
//  private val lsuBtHead = aluQueryWidth
//  private val mduBtHead = aluQueryWidth + lsuQueryWidth
//  private val bruBtHead = aluQueryWidth + lsuQueryWidth + mduQueryWidth
//
//  val aluIssueQueue = Module(new AluIssueQueue(16, aluDetectWidth))
//  val aluIssue      = Module(new AluIssue(aluDetectWidth))
//  val aluPipeline   = for (i <- 0 until nAluPl) yield Module(new AluPipeline)
//
//  val lsuIssueQueue = Module(new LSUIssueQueue(16, lsuDetectWidth))
//  val lsuIssue      = Module(new LsuIssue(lsuDetectWidth))
//  val lsuPipeline   = Module(new LsuPipeline)
//
//  val mduIssueQueue = Module(new MduIssueQueue(16))
//  val mduIssue      = Module(new MduIssue)
//  val mduTop        = Module(new MDUTop)
//
//  val bruIssueQueue = Module(new BruIssueQueue( 8, 4))
//  val bruIssue      = Module(new BruIssue(4))
//  val bruPipeline   = Module(new BruPipeline(nBranchCount))
//
//  val preFlush = extFlush || bruPipeline.io.brMissPred
//
//  dispatch.io.out0 <> aluIssueQueue.io.enq(0)
//  dispatch.io.out1 <> aluIssueQueue.io.enq(1)
//  dispatch.io.out2 <> lsuIssueQueue.io.enq(0)
//  dispatch.io.out3 <> mduIssueQueue.io.enq(0)
//  dispatch.io.out4 <> bruIssueQueue.io.enq
//
//
//  //---------------- AluIssueQueue + AluPipelines ------------------ //
//
//  aluIssue.io.detect     := aluIssueQueue.io.data
//  aluIssue.io.opReady    := aluIssueQueue.io.opReady
//  aluIssueQueue.io.issue := aluIssue.io.issue
//
//  for (i <- 0 until aluQueryWidth) {
//    aluIssueQueue.io.bt(i) <> busyTable.io.read(i)
//  }
//
//  aluIssueQueue.io.btExt(0) <> busyTable.io.extRead(0)
//
//  val aluIssueStage = Module(new StageReg(Vec(nAluPl, Valid(new AluEntry))))
//  aluIssueStage.io.in := aluIssue.io.out
//
//  for (i <- 0 until 2 * aluQueryWidth) {
//    aluIssueQueue.io.waken(i).awaken :=
//      (aluIssueQueue.io.waken(i).addr === aluIssue.io.awake(0).bits && aluIssue.io.awake(0).valid) ||
//      (aluIssueQueue.io.waken(i).addr === aluIssue.io.awake(1).bits && aluIssue.io.awake(1).valid)
//  }
//
//  val bypass = Wire(Vec(nAluPl, new BypassPair))
//
//  // aluPipeline 默认注册在其他组件(regfile, busyTable)接口的低位
//
//  for (i <- 0 until nAluPl) {
//    aluPipeline(i).io.uop := aluIssueStage.io.data(i)
//    bypass(i)             := aluPipeline(i).io.bypass.out
//
//    aluPipeline(i).io.prfRead <> regfile.io.read(i)
//    aluPipeline(i).io.prfReadExt <> regfile.io.extRead(i)
//    aluPipeline(i).io.bypass.in := bypass
//    regfile.io.write(i)         := aluPipeline(i).io.wb.prf
//    busyTable.io.checkOut(i)    := aluPipeline(i).io.wb.busyTable
//    rob.io.setComplete(i)       := aluPipeline(i).io.wb.rob
//  }
//
//  aluIssueStage.io.valid := 1.B
//
//  // -----------------BRU------------------//
//
//  for (i <- 0 until 5) {
//    bruIssueQueue.io.bt(i) <> busyTable.io.read(bruBtHead+i)
//  }
//
//  for (i <- 0 until 2 * 5) {
//    bruIssueQueue.io.waken(i).awaken :=
//      (bruIssueQueue.io.waken(i).addr === aluIssue.io.awake(0).bits && aluIssue.io.awake(0).valid) ||
//        (bruIssueQueue.io.waken(i).addr === aluIssue.io.awake(1).bits && aluIssue.io.awake(1).valid)
//  }
//
//  bruIssue.io.detect  := bruIssueQueue.io.data
//  bruIssue.io.opReady := bruIssueQueue.io.opReady
//  bruIssueQueue.io.issue := bruIssue.io.issue
//
//  bruPipeline.io.uop <> bruIssue.io.out
//
//  bruPipeline.io.prfRead <> regfile.io.read(4)
//  bruPipeline.io.bypass := bypass
//
//  rob.io.setComplete(3)     := bruPipeline.io.wb.rob
//  regfile.io.write(3)       := bruPipeline.io.wb.prf
//  busyTable.io.checkOut(3)  := bruPipeline.io.wb.busyTable
//
//  bruPipeline.io.brNowMask := rename.io.brMask
//
//  val renameFlush = WireInit(0.U.asTypeOf(new RenameFlush(nBranchCount)))
//  renameFlush.extFlush  := extFlush
//  renameFlush.brRestore := bruPipeline.io.brMissPred
//  renameFlush.brTag     := bruPipeline.io.brTag
//  renameFlush.brMask    := bruPipeline.io.brOriginalMask
//  val robFlush    = WireInit(0.U.asTypeOf(new ROBFlush()))
//  robFlush.extFlush     := extFlush
//  robFlush.brMissPred   := bruPipeline.io.brMissPred
//  robFlush.brRobAddr    := bruPipeline.io.brRob
//  val exeFlush   = WireInit(0.U.asTypeOf(new ExecFlush(nBranchCount)))
//  exeFlush.extFlush     := extFlush
//  exeFlush.brMissPred   := bruPipeline.io.brMissPred
//  exeFlush.brMask       := bruPipeline.io.brMask
//
//  bruIssue.io.flush      := exeFlush
//  bruPipeline.io.flush   := exeFlush
//
//  rename.io.flush    := renameFlush
//  busyTable.io.flush := renameFlush
//
//  when (commit.io.branch.restore.address.valid) {
//    // printf(p"commit restore to ${Hexadecimal(commit.io.branch.restore.address.bits)}\n")
//    io.branchCommit.restore := commit.io.branch.restore
//  } .otherwise {
////    when (bruPipeline.io.brMissPred) {
////      printf(p"bru restore to ${Hexadecimal(bruPipeline.io.brBta)}\n")
////    }
//    io.branchCommit.restore.address.valid := bruPipeline.io.brMissPred
//    io.branchCommit.restore.address.bits  := bruPipeline.io.brBta
//  }
//
//  rob.io.flush  := robFlush
//
//  aluIssueQueue.io.flush := exeFlush
//  lsuIssueQueue.io.flush := exeFlush
//  bruIssueQueue.io.flush := exeFlush
//
//  // ---------------- LSU ------------------ //
//  lsuIssue.io.detect        := lsuIssueQueue.io.data
//  lsuIssue.io.opReady       := lsuIssueQueue.io.opReady
//  lsuIssueQueue.io.issue(0) := lsuIssue.io.issue
//  lsuIssue.io.flush         := exeFlush
//  lsuIssueQueue.io.flush    := exeFlush
//  for (i <- 0 until lsuQueryWidth) {
//    lsuIssueQueue.io.bt(i) <> busyTable.io.read(lsuBtHead + i)
//  }
//  lsuIssueQueue.io.btExt(0) <> busyTable.io.extRead(1)
//  lsuPipeline.io.uop <> lsuIssue.io.out
//  lsuPipeline.io.tlbDataResp <> io.tlb.dataResp
//  lsuPipeline.io.tlbDataReq <> io.tlb.dataReq
//
//  lsuPipeline.io.sbRetireReady <> commit.io.sbRetireReady
//  lsuPipeline.io.sbRetire := commit.io.sbRetire
//
//  lsuPipeline.io.flush       := exeFlush
//  lsuPipeline.io.prfRead <> regfile.io.read(2)
//  lsuPipeline.io.prfReadExt <> regfile.io.extRead(2)
//  regfile.io.write(4)      := lsuPipeline.io.wb.prf(0)
//  regfile.io.write(5)      := lsuPipeline.io.wb.prf(1)
//  busyTable.io.checkOut(4) := lsuPipeline.io.wb.busyTable(0)
//  busyTable.io.checkOut(5) := lsuPipeline.io.wb.busyTable(1)
//  busyTable.io.checkOut(6) := lsuPipeline.io.wb.busyTable(2)
//  rob.io.setComplete(4)    := lsuPipeline.io.wb.rob(0)
//  rob.io.setComplete(5)    := lsuPipeline.io.wb.rob(1)
//  rob.io.setComplete(6)    := lsuPipeline.io.wb.rob(2)
//
//  aluIssue.io.stallReq := 0.B
//  bruIssue.io.stallReq := 0.B
//  lsuIssue.io.stallReq := 0.B
//  mduIssue.io.stallReq := 0.B
//
//  lsuPipeline.io.sbKilled := 0.B
//
//  // ---------------- Data Cache ------------------ //
//  val dCacheReqWire    = WireInit(0.U.asTypeOf(new DCacheReqv2))
//  val dCacheReqValid   = WireInit(0.B)
//  val dCacheReqHazard  = WireInit(0.B)
//  val unCacheReqWire   = WireInit(0.U.asTypeOf(new DCacheReqv2))
//  val unCacheReqValid  = WireInit(0.B)
//  val unCacheReqHazard = WireInit(0.B)
//
//  // TODO: priority
//  // dCache read signals
//  dCacheReqValid   := lsuPipeline.io.dcache.req.valid
//  dCacheReqWire    := lsuPipeline.io.dcache.req.bits
//  dCacheReqHazard  := lsuPipeline.io.dcache.req.valid
//  lsuPipeline.io.dcache.req.ready := io.dcache.req.ready
//  lsuPipeline.io.dcache.resp      := io.dcache.resp
//  lsuPipeline.io.dcache.inform    := io.dcache.inform
//
//  unCacheReqValid  := lsuPipeline.io.uncache.req.valid
//  unCacheReqWire   := lsuPipeline.io.uncache.req.bits
//  unCacheReqHazard := lsuPipeline.io.uncache.req.valid
//  lsuPipeline.io.uncache.req.ready := io.uncache.req.ready
//  lsuPipeline.io.uncache.resp      := io.uncache.resp
//  lsuPipeline.io.uncache.inform    := io.uncache.inform
//
//  // dCache write signals
//  val cacheReq = WireInit(0.U.asTypeOf(Valid(new DCacheReqv2)))
//  cacheReq.valid          := lsuPipeline.io.wbHeadEntry.valid
//  cacheReq.bits.addr      := Cat(lsuPipeline.io.wbHeadEntry.bits.addr, 0.U(2.W))
//  cacheReq.bits.validMask := lsuPipeline.io.wbHeadEntry.bits.valid
//  cacheReq.bits.writeData := lsuPipeline.io.wbHeadEntry.bits.data
//  cacheReq.bits.meta      := 0.U.asTypeOf(new MemReq)
//
//  val writeUnCache = cacheReq.valid && lsuPipeline.io.wbHeadEntry.bits.unCache
//  val writeDCache  = cacheReq.valid && !lsuPipeline.io.wbHeadEntry.bits.unCache
//  when (!dCacheReqHazard) {
//    dCacheReqValid := writeDCache
//    dCacheReqWire  := cacheReq.bits
//  }
//
//  when (!unCacheReqHazard) {
//    unCacheReqValid := writeUnCache
//    unCacheReqWire  := cacheReq.bits
//  }
//
//  val cacheReady =
//    (writeUnCache && !unCacheReqHazard && io.uncache.req.ready ) ||
//    (writeDCache  && !dCacheReqHazard  && io.dcache.req.ready)
//
//  lsuPipeline.io.wbHeadEntry.ready := cacheReady
//
////  when (dCacheReqHazard) {
////    printf(p"dCache write: ${Hexadecimal(cacheReq.bits.addr)}(${cacheReq.bits.validMask}) : ${Hexadecimal(cacheReq.bits.writeData)}\n")
////  }
////
////  when(unCacheReqHazard) {
////    printf(p"unCache write: ${Hexadecimal(cacheReq.bits.addr)}(${cacheReq.bits.validMask}) : ${Hexadecimal(cacheReq.bits.writeData)}\n")
////  }
//
//  io.dcache.req.valid := dCacheReqValid
//  io.dcache.req.bits  := dCacheReqWire
//  io.uncache.req.valid := unCacheReqValid
//  io.uncache.req.bits := unCacheReqWire
//
//  io.icacheInv <> mduTop.io.icacheInv
//  io.dcacheInv <> mduTop.io.dcacheInv
//
//  dispatch.io.flush := preFlush
//
//  // Cache Flush need to stall for one cycle
//  val cacheFlush = RegNext(exeFlush, 0.U.asTypeOf(new ExecFlush(nBranchCount)))
//
//  io.dcache.flush := cacheFlush
//  io.uncache.flush := cacheFlush
//
//
//  // ---------------- MDU ------------------ //
//  mduIssue.io.opReady := mduIssueQueue.io.opReady
//  mduIssue.io.in <> mduIssueQueue.io.deq
//  mduIssue.io.flush := exeFlush
//
//  mduTop.io.in <> mduIssue.io.out
//
//  for (i <- 0 until 3) {
//    mduIssueQueue.io.bt(i) <> busyTable.io.read(mduBtHead + i)
//  }
//  mduTop.io.prf <> regfile.io.read(3)
//  mduTop.io.cp0Read <> io.cp0Read
//  io.cp0Write       := mduTop.io.cp0Write
//  mduTop.io.tlbReq <> io.tlb.tlbReq
//  mduTop.io.cp0TLBReq <> io.cp0TLBReq
//
//  rob.io.setComplete(2)    := mduTop.io.wb.rob
//  regfile.io.write(2)      := mduTop.io.wb.prf
//  busyTable.io.checkOut(2) := mduTop.io.wb.busyTable
//
//  mduIssueQueue.io.flush := exeFlush
//  mduTop.io.flush        := exeFlush
//
//  mduTop.io.hlW    := commit.io.hlW // from commit
//
//
//  // debug
//  io.debug_rob_valid_0 := rob.io.read(0).valid
//  io.debug_rob_pc_0    := rob.io.read(0).bits.pc
//  io.debug_rob_instr_0 := rob.io.read(0).bits.instruction
//  io.debug_rob_valid_1 := rob.io.read(1).valid
//  io.debug_rob_pc_1    := rob.io.read(1).bits.pc
//  io.debug_rob_instr_1 := rob.io.read(1).bits.instruction
//
//  io.debug_ls_fire    := io.dcache.req.fire
//  io.debug_ls_addr    := io.dcache.req.bits.addr
//  io.debug_valid_mask := io.dcache.req.bits.validMask.asUInt
//  io.debug_store_data := io.dcache.req.bits.writeData
//  io.debug_resp_valid := io.dcache.resp.valid
//  io.debug_load_data  := io.dcache.resp.bits.loadData
//
//  io.debug_exception := rob.io.read(0).bits.exception.asUInt
//  io.debug_badvaddr  := rob.io.read(0).bits.badvaddr
//
//  // flush
//  /// other interface
//  decodeSync.io.flush      := preFlush
//  decodeRenameQ.io.flush   := preFlush
//
//  val dispatchFlush = Wire(new DispatchFlush)
//  dispatchFlush.extFlush := extFlush
//  dispatchFlush.brMissPred := bruPipeline.io.brMissPred
//  dispatchFlush.brOriginalMask := bruPipeline.io.brOriginalMask
//
//  renameDispatchQ.io.flush := dispatchFlush
//
//  /** potential bug **/
//  aluIssue.io.flush := exeFlush
//  aluIssueStage.io.flush   := preFlush
//  aluIssueQueue.io.flush   := exeFlush
//  for (i <- 0 to 1) yield {
//    aluPipeline(i).io.flush := exeFlush
//  }
//
//  io.debug_dcache_state := 0.U
//  io.debug_out_num := 0.U
}
