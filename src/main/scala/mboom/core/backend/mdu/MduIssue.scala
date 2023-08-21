package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{nBranchCount, robEntryNumWidth}
import mboom.core.backend.decode._
import mboom.core.components.{MuxStageReg, MuxStageRegMode, StageReg, StageRegv2}
import mboom.core.backend.{ExecFlush, utils}
import mboom.core.backend.utils.{ExecuteUtil, IssueReady}

class MduIssue extends Module {
  val io = IO(new Bundle {
    // 接收窗口; ready指定是否发射
    val in      = Flipped(Decoupled(new MicroMDUOp))
    val opReady = Input(new IssueReady)

    val out = Decoupled(new MicroMDUOp)

    val stallReq = Input(Bool())
    val stallRobDiffer = Input(UInt(robEntryNumWidth.W))

    val flush = Input(new ExecFlush(nBranchCount))
  })

  val uop      = WireInit(io.in.bits)
  val avalible = Wire(Bool())
  // 计算 availible
  val op1Avalible = io.opReady.op1Rdy
  val op2Avalible = io.opReady.op2Rdy

  val issueStuck =
    io.flush.brMissPred || io.stallReq

  avalible :=
    io.in.valid &&
    op1Avalible &&
    op2Avalible &&
    !issueStuck

  val stage = Module(new StageRegv2(new MicroMDUOp))

  // datapath
  stage.io.in.bits  := uop
  stage.io.in.valid := avalible

  io.out <> stage.io.out
  stage.io.flush := io.flush

  // 双端decoupled信号生成
  io.in.ready := stage.io.in.ready && avalible

}
