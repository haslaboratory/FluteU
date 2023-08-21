package mboom.core.backend

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
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

class Backendv2(nWays: Int) extends Module {
  require(nWays == 2)
  val io = IO(new Bundle {
    val ibuffer = Vec(nWays, Flipped(DecoupledIO(new IBEntryNew)))
    val branchCommit = Output(new BranchCommitWithBPU)
    val cp0 = Flipped(new CP0WithCommit)
    val cp0IntrReq = Input(Bool())
    val cp0EretReq = Input(Bool())
    val cp0Read = Flipped(new CP0Read)
    val cp0Write = Output(new CP0Write)

    val cp0TLBReq = ValidIO(new CP0TLBReq)

    val tlb = Flipped(new TLBWithCore)

    val icacheInv = Flipped(new CacheInvalidate)
    // val dcacheInv = Flipped(new CacheInvalidate)

    val dcacheAxi      = AXIIO.master()
    val dcacheRelease  = Output(Bool())
    val uncacheAxi     = AXIIO.master()
    val uncacheRelease = Output(Bool())
    // debug
    val debug_rob_valid_0 = Output(Bool())
    val debug_rob_pc_0 = Output(UInt(32.W))
    val debug_rob_instr_0 = Output(UInt(32.W))
    val debug_rob_valid_1 = Output(Bool())
    val debug_rob_pc_1 = Output(UInt(32.W))
    val debug_rob_instr_1 = Output(UInt(32.W))

    val debug_ls_fire = Output(Bool())
    val debug_ls_addr = Output(UInt(32.W))
    val debug_valid_mask = Output(UInt(4.W))
    val debug_store_data = Output(UInt(32.W))
    val debug_resp_valid = Output(Bool())
    val debug_load_data = Output(UInt(32.W))

    val debug_exception = Output(UInt(11.W))
    val debug_badvaddr = Output(UInt(32.W))

    val debug_issue_valid = Output(Bool())
    val debug_prf_valid   = Output(Bool())
  })

  val decoders = for (i <- 0 until nWays) yield Module(new Decoder)
  val decodeSync = Module(new DecodeSync(nWays))

  val rename = Module(new Renamev2(nWays = nWays, nCommit = nWays, nBrCount = 4))
  // alu + bru = 10
  val busyTable0 = Module(new BusyTablev2(nRead = 10, nExtRead = 1, nCheckIn = 3, nCheckOut = 5, nBrCount = nBranchCount))
  // lsu + mdu = 6 + 3
  val busyTable1 = Module(new BusyTablev2(nRead = 9, nExtRead = 1, nCheckIn = 3, nCheckOut = 5, nBrCount = nBranchCount))
  val regfile = Module(new RegFile(numRead = 4, numWrite = 4, numExtRead = 3))

  val dispatch = Module(new Dispatchv2)

  // val branchUnit = Module(new BranchRestore(nBranchCount))

  val rob = Module(
    new ROB(numEntries = robEntryAmount, numRead = 2, numWrite = 2, numSetComplete = 4)
  )
  val commit = Module(new Commit(nCommit = nWays))

  val decodeRenameQ = Module(new DecodeRenameQueue())
  // val renameStage = Module(new StageReg(Vec(nWays, Valid(new RenameOp))))
  val renameDispatchQ = Module(new RenameDispatchQueue(nWays = nWays))

  val extFlush = io.cp0IntrReq || io.cp0EretReq || commit.io.branch.restore.address.valid

  for (i <- 0 until nWays) {
    decoders(i).io.instr := io.ibuffer(i).bits
    decodeRenameQ.io.enq(i).bits := decoders(i).io.microOp
    decodeRenameQ.io.enq(i).valid := io.ibuffer(i).valid
  }
  decoders(0).io.syncIn := decodeSync.io.out
  decoders(1).io.syncIn := decoders(0).io.syncOut
  for (i <- 0 until nWays) {
    decodeSync.io.in(i).valid := io.ibuffer(i).fire
    decodeSync.io.in(i).bits := decoders(i).io.syncOut
  }

  rename.io.decode <> decodeRenameQ.io.deq
  busyTable0.io.checkIn(0)   := rename.io.checkIn(0)
  busyTable0.io.checkIn(1)   := rename.io.checkIn(1)
  busyTable0.io.brRequest := rename.io.brRequest
  busyTable0.io.brTag     := rename.io.brTag
  busyTable1.io.checkIn(0) := rename.io.checkIn(0)
  busyTable1.io.checkIn(1) := rename.io.checkIn(1)
  busyTable1.io.brRequest := rename.io.brRequest
  busyTable1.io.brTag     := rename.io.brTag

  renameDispatchQ.io.enq <> rename.io.dispatch
  renameDispatchQ.io.robValid0 := rob.io.read(0).valid
  renameDispatchQ.io.robValid1 := rob.io.read(1).valid

  dispatch.io.in <> renameDispatchQ.io.deq
  rob.io.write <> dispatch.io.rob

  // stall
  // val stall = rename.io.stallReq || dispatch.io.stallReq
  for (i <- 0 until nWays) {
    io.ibuffer(i).ready := decodeRenameQ.io.enq(i).ready
  }
  // decodeStage.io.valid := !stall
  // renameStage.io.valid := !(dispatch.io.stallReq)

  //----------------------------------commit------------------
  commit.io.rob <> rob.io.read
  rename.io.commit := commit.io.commit
  io.branchCommit.train := commit.io.branch.train

  io.cp0 <> commit.io.cp0
  commit.io.flush := extFlush

  //------------------------issue------------------------------------
  private val aluDetectWidth = 8
  private val lsuDetectWidth = 4
  private val nAluPl = 2 // number of alu piplines
  private val aluQueryWidth = aluDetectWidth + 2
  private val lsuQueryWidth = lsuDetectWidth + 2
  private val mduQueryWidth = 1 + 2
  private val bruBtHead = aluQueryWidth
  private val lsuBtHead = 0
  private val mduBtHead = lsuQueryWidth

  val aluIssueQueue = Module(new AluIssueQueue(10, aluDetectWidth))
  val aluIssue = Module(new AluIssue(aluDetectWidth))
  val aluPipeline = for (i <- 0 until nAluPl) yield Module(new AluPipeline(i))

  val lsuIssueQueue = Module(new LSUIssueQueue(8, lsuDetectWidth))
  val lsuIssue = Module(new LsuIssue(lsuDetectWidth))
//  val lsuPipeline = Module(new LsuPipeline)
  val lsuTop = Module(new LsuTop)

  val mduIssueQueue = Module(new MduIssueQueue(8))
  val mduIssue = Module(new MduIssue)
  val mduTop = Module(new MDUTop)

//  val bruIssueQueue = Module(new BruIssueQueue(8, 4))
//  val bruIssue = Module(new BruIssue(4))
//  val bruPipeline = Module(new BruPipeline(nBranchCount))

  val preFlush = extFlush || 0.B // bruPipeline.io.brMissPred

  dispatch.io.out0 <> aluIssueQueue.io.enq(0)
  dispatch.io.out1 <> aluIssueQueue.io.enq(1)
  dispatch.io.out2 <> lsuIssueQueue.io.enq(0)
  dispatch.io.out3 <> mduIssueQueue.io.enq(0)
  // dispatch.io.out4 <> bruIssueQueue.io.enq

  aluIssueQueue.io.robHead := rob.io.head
  lsuIssueQueue.io.robHead := rob.io.head
  // bruIssueQueue.io.robHead := rob.io.head
  mduIssueQueue.io.robHead := rob.io.head

  aluIssueQueue.io.potentialStallReg := lsuTop.io.potentialStallReg
  lsuIssueQueue.io.potentialStallReg := lsuTop.io.potentialStallReg
  // bruIssueQueue.io.potentialStallReg := lsuTop.io.potentialStallReg
  mduIssueQueue.io.potentialStallReg := lsuTop.io.potentialStallReg


  //---------------- AluIssueQueue + AluPipelines ------------------ //

  aluIssue.io.detect := aluIssueQueue.io.data
  aluIssue.io.opReady := aluIssueQueue.io.opReady
  aluIssueQueue.io.issue := aluIssue.io.issue

  for (i <- 0 until aluQueryWidth) {
    aluIssueQueue.io.bt(i) <> busyTable0.io.read(i)
  }

  aluIssueQueue.io.btExt(0) <> busyTable0.io.extRead(0)

  val aluIssueStage = Module(new StageReg(Vec(nAluPl, Valid(new AluEntry))))
  aluIssueStage.io.in := aluIssue.io.out

  for (i <- 0 until 2 * aluQueryWidth) {
    aluIssueQueue.io.waken(i).awaken :=
      (aluIssueQueue.io.waken(i).addr === aluIssue.io.awake(0).bits && aluIssue.io.awake(0).valid) ||
        (aluIssueQueue.io.waken(i).addr === aluIssue.io.awake(1).bits && aluIssue.io.awake(1).valid)
  }

  for (i <- 0 until nAluPl) {
    busyTable0.io.checkOut(i) := aluIssue.io.awake(i)
  }

  val bypassData = Wire(Vec(nAluPl, new BypassPair))
  val bypassReg  = Wire(Vec(nAluPl, Valid(UInt(phyRegAddrWidth.W))))

  // aluPipeline 默认注册在其他组件(regfile, busyTable)接口的低位

  for (i <- 0 until nAluPl) {
    aluPipeline(i).io.uop := aluIssueStage.io.data(i)
    bypassData(i) := aluPipeline(i).io.bypass.dataOut
    bypassReg(i)  := aluPipeline(i).io.bypass.regOut

    aluPipeline(i).io.prfRead <> regfile.io.read(i)
    aluPipeline(i).io.prfReadExt <> regfile.io.extRead(i)
    aluPipeline(i).io.bypass.dataIn := bypassData
    aluPipeline(i).io.bypass.regIn  := bypassReg
    regfile.io.write(i) := aluPipeline(i).io.wb.prf
    // busyTable0.io.checkOut(i) := aluPipeline(i).io.wb.busyTable
    busyTable1.io.checkOut(i) := aluPipeline(i).io.wb.busyTable
    rob.io.setComplete(i) := aluPipeline(i).io.wb.rob
  }

  aluIssueStage.io.valid := 1.B

  // -----------------BRU------------------//

//  for (i <- 0 until 5) {
//    bruIssueQueue.io.bt(i) <> busyTable0.io.read(bruBtHead + i)
//  }
//
//  for (i <- 0 until 2 * 5) {
//    bruIssueQueue.io.waken(i).awaken :=
//      (bruIssueQueue.io.waken(i).addr === aluIssue.io.awake(0).bits && aluIssue.io.awake(0).valid) ||
//        (bruIssueQueue.io.waken(i).addr === aluIssue.io.awake(1).bits && aluIssue.io.awake(1).valid)
//  }
//
//  bruIssue.io.detect := bruIssueQueue.io.data
//  bruIssue.io.opReady := bruIssueQueue.io.opReady
//  bruIssueQueue.io.issue := bruIssue.io.issue
//
//  bruPipeline.io.uop <> bruIssue.io.out
//  bruPipeline.io.prfRead <> regfile.io.read(4)
//  bruPipeline.io.bypass := bypass
//
//  rob.io.setComplete(3) := bruPipeline.io.wb.rob
//  regfile.io.write(3) := bruPipeline.io.wb.prf
//  busyTable0.io.checkOut(3) := bruPipeline.io.wb.busyTable
//  busyTable1.io.checkOut(3) := bruPipeline.io.wb.busyTable
//
//  bruPipeline.io.brNowMask := rename.io.brMask

  val renameFlush = WireInit(0.U.asTypeOf(new RenameFlush(nBranchCount)))
  renameFlush.extFlush := extFlush
  renameFlush.brRestore := 0.U // bruPipeline.io.brMissPred
  renameFlush.brTag := 0.U // bruPipeline.io.brTag
  renameFlush.brMask := 0.U // bruPipeline.io.brOriginalMask
  val robFlush = WireInit(0.U.asTypeOf(new ROBFlush()))
  robFlush.extFlush := extFlush
  robFlush.brMissPred := 0.U // bruPipeline.io.brMissPred
  robFlush.brRobAddr := 0.U // bruPipeline.io.brRob
  val exeFlush = WireInit(0.U.asTypeOf(new ExecFlush(nBranchCount)))
  exeFlush.extFlush := extFlush
  exeFlush.brMissPred := 0.U // bruPipeline.io.brMissPred
  exeFlush.brMask := 0.U // bruPipeline.io.brMask

//  bruIssue.io.flush := exeFlush
//  bruPipeline.io.flush := exeFlush

  rename.io.flush := renameFlush
  busyTable0.io.flush := renameFlush
  busyTable1.io.flush := renameFlush

  io.branchCommit.restore := commit.io.branch.restore

//  when(commit.io.branch.restore.address.valid) {
//    // printf(p"commit restore to ${Hexadecimal(commit.io.branch.restore.address.bits)}\n")
//    io.branchCommit.restore := commit.io.branch.restore
//  }.otherwise {
//    //    when (bruPipeline.io.brMissPred) {
//    //      printf(p"bru restore to ${Hexadecimal(bruPipeline.io.brBta)}\n")
//    //    }
//    io.branchCommit.restore.address.valid := bruPipeline.io.brMissPred
//    io.branchCommit.restore.address.bits := bruPipeline.io.brBta
//  }

  rob.io.flush := robFlush

  aluIssueQueue.io.flush := exeFlush
  lsuIssueQueue.io.flush := exeFlush
  // bruIssueQueue.io.flush := exeFlush

  // ---------------- LSU ------------------ //
  lsuIssue.io.detect := lsuIssueQueue.io.data
  lsuIssue.io.opReady := lsuIssueQueue.io.opReady
  lsuIssueQueue.io.issue(0) := lsuIssue.io.issue
  lsuIssue.io.flush := exeFlush
  lsuIssueQueue.io.flush := exeFlush
  for (i <- 0 until lsuQueryWidth) {
    lsuIssueQueue.io.bt(i) <> busyTable1.io.read(lsuBtHead + i)
  }
  lsuIssueQueue.io.btExt(0) <> busyTable1.io.extRead(0)
  lsuTop.io.uop <> lsuIssue.io.out
  // lsuTop.io.robHead := rob.io.head

  lsuTop.io.tlbDataResp <> io.tlb.dataResp
  lsuTop.io.tlbDataReq <> io.tlb.dataReq

  lsuTop.io.dRetire := commit.io.dRetire
  lsuTop.io.unRetire := commit.io.unRetire

  lsuTop.io.flush := exeFlush
  lsuTop.io.prfRead <> regfile.io.read(2)
  lsuTop.io.prfReadExt <> regfile.io.extRead(2)
  regfile.io.write(3)      := lsuTop.io.wb.prf
  busyTable0.io.checkOut(3) := lsuTop.io.wb.busyTableSpec
  busyTable0.io.checkOut(4) := lsuTop.io.wb.busyTableFact
  busyTable0.io.checkIn(2)  := lsuTop.io.wb.busyTableCorr
  busyTable1.io.checkOut(3) := lsuTop.io.wb.busyTableSpec
  busyTable1.io.checkOut(4) := lsuTop.io.wb.busyTableFact
  busyTable1.io.checkIn(2) := lsuTop.io.wb.busyTableCorr
  rob.io.setComplete(3)    := lsuTop.io.wb.rob

  io.dcacheAxi      <> lsuTop.io.dcacheAxi
  io.dcacheRelease  := lsuTop.io.dcacheRelease
  io.uncacheAxi     <> lsuTop.io.uncacheAxi
  io.uncacheRelease := lsuTop.io.uncacheRelease

  io.icacheInv <> mduTop.io.icacheInv
  // io.dcacheInv <> mduTop.io.dcacheInv
  mduTop.io.dcacheInv := DontCare

  aluIssue.io.stallReq := lsuTop.io.stallReq
  aluIssue.io.stallRobDiffer := 0.U // lsuTop.io.stallRobDiffer
//  bruIssue.io.stallReq := lsuTop.io.stallReq
//  bruIssue.io.stallRobDiffer := 0.U // lsuTop.io.stallRobDiffer
  lsuIssue.io.stallReq := lsuTop.io.stallReq
  lsuIssue.io.stallRobDiffer := 0.U // lsuTop.io.stallRobDiffer
  mduIssue.io.stallReq := lsuTop.io.stallReq
  mduIssue.io.stallRobDiffer := 0.U // lsuTop.io.stallRobDiffer

  dispatch.io.flush := preFlush

  // ---------------- MDU ------------------ //
  mduIssue.io.opReady := mduIssueQueue.io.opReady
  mduIssue.io.in <> mduIssueQueue.io.deq
  mduIssue.io.flush := exeFlush

  mduTop.io.in <> mduIssue.io.out

  for (i <- 0 until 3) {
    mduIssueQueue.io.bt(i) <> busyTable1.io.read(mduBtHead + i)
  }
  mduTop.io.prf <> regfile.io.read(3)
  mduTop.io.cp0Read <> io.cp0Read
  io.cp0Write := mduTop.io.cp0Write
  mduTop.io.tlbReq <> io.tlb.tlbReq
  mduTop.io.cp0TLBReq <> io.cp0TLBReq

  rob.io.setComplete(2) := mduTop.io.wb.rob
  regfile.io.write(2) := mduTop.io.wb.prf
  busyTable0.io.checkOut(2) := mduTop.io.wb.busyTable
  busyTable1.io.checkOut(2) := mduTop.io.wb.busyTable

  mduIssueQueue.io.flush := exeFlush
  mduTop.io.flush := exeFlush

  mduTop.io.hlW := commit.io.hlW // from commit


  // debug

  // flush
  /// other interface
  decodeSync.io.flush      := preFlush
  decodeRenameQ.io.flush   := preFlush

  val dispatchFlush = Wire(new DispatchFlush)
  dispatchFlush.extFlush   := extFlush
  dispatchFlush.brMissPred := 0.U // bruPipeline.io.brMissPred
  dispatchFlush.brOriginalMask := 0.U // bruPipeline.io.brOriginalMask

  renameDispatchQ.io.flush := dispatchFlush

  /** potential bug * */
  aluIssue.io.flush      := exeFlush
  aluIssueStage.io.flush := preFlush
  aluIssueQueue.io.flush := exeFlush
  for (i <- 0 to 1) yield {
    aluPipeline(i).io.flush := exeFlush
  }

  io.debug_rob_valid_0 := rob.io.read(0).valid && !extFlush
  io.debug_rob_pc_0    := rob.io.read(0).bits.pc
  io.debug_rob_instr_0 := rob.io.read(0).bits.instruction
  io.debug_rob_valid_1 := rob.io.read(1).valid && !extFlush
  io.debug_rob_pc_1    := rob.io.read(1).bits.pc
  io.debug_rob_instr_1 := rob.io.read(1).bits.instruction

  io.debug_exception := rob.io.read(0).bits.exception.asUInt
  io.debug_badvaddr := rob.io.read(0).bits.badvaddr

  io.debug_ls_fire    := lsuTop.io.debug_ls_fire
  io.debug_ls_addr    := lsuTop.io.debug_ls_addr
  io.debug_valid_mask := lsuTop.io.debug_valid_mask
  io.debug_store_data := lsuTop.io.debug_store_data
  io.debug_resp_valid := lsuTop.io.debug_resp_valid
  io.debug_load_data  := lsuTop.io.debug_load_data

  io.debug_issue_valid := lsuIssueQueue.io.data(0).valid
  io.debug_prf_valid   := lsuIssue.io.out.valid
}
