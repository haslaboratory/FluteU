package mboom.core.backend.bru

import chisel3._
import chisel3.util._
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode._
import mboom.core.components.{MuxStageReg, MuxStageRegMode, StageRegv2}
import mboom.core.backend.utils.{ExecuteUtil, IssueReady}
import mboom.config.CPUConfig._

class BruIssue(detectWidth: Int) extends Module {
  val io = IO(new Bundle {
    val detect  = Input(Vec(detectWidth, Valid(new MicroBRUOp)))
    val opReady = Input(Vec(detectWidth, new IssueReady))

    val issue   = Output(Valid(UInt(log2Ceil(detectWidth).W)))
    val out     = Valid(new MicroBRUOp)

    val stallReq = Input(Bool())
    val stallRobDiffer = Input(UInt(robEntryNumWidth.W))

    val flush   = Input(new ExecFlush(nBranchCount))
  })

  val available = Wire(Vec(detectWidth, Bool()))

  val uops = VecInit(io.detect.map(_.bits))

  // 计算 availible
//  val op1Avalible = io.opReady.op1Rdy
//  val op2Avalible = io.opReady.op2Rdy
//  avalible := op1Avalible && op2Avalible

  for (i <- 0 until detectWidth) {
    val op1Available = io.opReady(i).op1Rdy
    val op2Available = io.opReady(i).op2Rdy

    val opAvailable = op1Available && op2Available

    available(i) :=
      io.detect(i).valid &&
      opAvailable
  }

  val issueStuck = Wire(Vec(detectWidth, Bool()))
  for (i <- 0 until detectWidth) {
    issueStuck(i) :=
      io.flush.brMissPred || io.stallReq
  }

  val canIssue   = available

  val issue      = PriorityEncoder(canIssue.asUInt)
  val issueValid = canIssue(issue) && !issueStuck(issue)

  val stage = Module(new StageRegv2(new MicroBRUOp))

  // datapath
  stage.io.in.bits  := uops(issue)
  stage.io.in.valid := issueValid

  io.out.bits  := stage.io.out.bits
  io.out.valid := stage.io.out.valid
  stage.io.out.ready := 1.B

  stage.io.flush := io.flush

  // 双端decoupled信号生成
  io.issue.bits  := issue
  io.issue.valid := stage.io.in.ready && issueValid
}
