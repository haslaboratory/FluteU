package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.phyRegAddrWidth
import mboom.core.backend.alu.AluEntry
import mboom.core.backend.decode.MicroALUOp

// TODO memory issue may be different
class IssueRdy(detectWidth: Int) extends Module {
  private val num = 1

  val io = IO(new Bundle {
    val detect  = Input(Vec(detectWidth, Valid(new MicroALUOp)))
    val opReady = Input(Vec(detectWidth, new IssueReady))

    val issue = Output(Vec(num, Valid(UInt(log2Ceil(detectWidth).W))))
    val out   = Output(Vec(num, Valid(new AluEntry)))
    val awake = Output(Vec(num, Valid(UInt(phyRegAddrWidth.W))))
  })

  val available = Wire(Vec(detectWidth, Bool()))

  val uops = VecInit(io.detect.map(_.bits))

  for (i <- 0 until detectWidth) {
    val op1Available = uops(i).baseOp.op1.valid || io.opReady(i).op1Rdy
    val op2Available = uops(i).baseOp.op2.valid || io.opReady(i).op2Rdy

    available(i) := op1Available && op2Available && io.detect(i).valid
  }

  val canIssue = available

  val issue = IssueUtil.selectFirstN(canIssue.asUInt, num)
  val issueV = canIssue(0)

//  when(issue(0) === issue(1)) {
//    issueV(1) := 0.B
//  }

  for (i <- 0 until num) {
    io.issue(i).bits := issue(i)
    io.issue(i).valid := issueV(i)

    io.out(i).bits.uop := uops(issue(i))
    io.out(i).bits.op1Awaken := DontCare
    io.out(i).bits.op2Awaken := DontCare
    io.out(i).valid := issueV(i)

    io.awake(i).valid := issueV(i) && uops(issue(i)).baseOp.regWriteEn
    io.awake(i).bits := uops(issue(i)).baseOp.writeRegAddr

  }
}

object IssueUtil {
  def selectFirstN(in: UInt, n: Int) = {
    PriorityEncoder(in)
  }

  def rotateWindow(width: Int, in: Vec[Bool], index: UInt) = {
    val out = WireInit(0.U.asTypeOf(Vec(width, Bool())))
    for (i <- 0 until width) {
      out(i) := in(i.U(width.W) + index)
    }
    out
  }
}