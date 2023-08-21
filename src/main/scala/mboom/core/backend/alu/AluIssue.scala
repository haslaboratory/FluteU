package mboom.core.backend.alu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{nBranchCount, phyRegAddrWidth, robEntryNumWidth}
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroALUOp
import mboom.core.backend.utils._

class OpAwaken extends Bundle {
  private val deqNum = 2 // ALU 流水线个数

  val awaken = Bool()
  val sel    = UInt(log2Ceil(deqNum).W)
}
class AluEntry extends Bundle {
  val uop       = new MicroALUOp
  val op1Awaken = new OpAwaken
  val op2Awaken = new OpAwaken
}
class AluIssue(detectWidth: Int) extends Module {
  private val numOfAluPipeline = 2

  val io = IO(new Bundle {
    val detect  = Input(Vec(detectWidth, Valid(new MicroALUOp)))
    val opReady = Input(Vec(detectWidth, new IssueReady))

    val issue = Output(Vec(numOfAluPipeline, Valid(UInt(log2Ceil(detectWidth).W))))
    val out   = Output(Vec(numOfAluPipeline, Valid(new AluEntry)))
    val awake = Output(Vec(numOfAluPipeline, Valid(UInt(phyRegAddrWidth.W))))

    val stallReq = Input(Bool())
    val stallRobDiffer = Input(UInt(robEntryNumWidth.W))
    val flush   = Input(new ExecFlush(nBranchCount))
  })

  val available = Wire(Vec(detectWidth, Bool()))

  val uops = VecInit(io.detect.map(_.bits))

  for (i <- 0 until detectWidth) {
    val op1Available = io.opReady(i).op1Rdy
    val op2Available = io.opReady(i).op2Rdy
    val opExtAvailable = io.opReady(i).opExtRdy

    available(i) :=
      op1Available &&
      op2Available &&
      opExtAvailable &&
      io.detect(i).valid
  }

  val canIssue = available

  val issue = AluIssueUtil.selectFirstN(canIssue.asUInt, numOfAluPipeline)
  val issueV = WireInit(VecInit(issue.map({ case a => canIssue(a) })))

  when(issue(0) === (detectWidth-1).U) {
    issueV(1) := 0.B
  }

  val issueStuck = Wire(Vec(detectWidth, Bool()))
  for (i <- 0 until detectWidth) {
    issueStuck(i) :=
      io.flush.brMissPred || io.stallReq
  }


  for (i <- 0 until numOfAluPipeline) {
    io.issue(i).bits := issue(i)
    io.issue(i).valid := issueV(i) && !issueStuck(issue(i))

    io.out(i).bits.uop := uops(issue(i))
    io.out(i).bits.op1Awaken := DontCare
    io.out(i).bits.op2Awaken := DontCare
    io.out(i).valid := issueV(i) && !issueStuck(issue(i))

    io.awake(i).valid := issueV(i) && !issueStuck(issue(i)) && uops(issue(i)).baseOp.regWriteEn
    io.awake(i).bits := uops(issue(i)).baseOp.writeRegAddr

  }
}

object AluIssueUtil {
  def selectFirstN(in: UInt, n: Int) = {
    assert(n == 2)
    val sels = Wire(Vec(n, UInt(log2Ceil(in.getWidth).W)))
    sels(0) := PriorityEncoder(in)
    val mask = in & (~(1.U << sels(0)).asUInt)
    sels(1) := PriorityEncoder(mask)
    sels
  }

  def rotateWindow(width: Int, in: Vec[Bool], index: UInt) = {
    val out = WireInit(0.U.asTypeOf(Vec(width, Bool())))
    for (i <- 0 until width) {
      out(i) := in(i.U(width.W) + index)
    }
    out
  }
}
