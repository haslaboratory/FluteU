package mboom.core.backend.alu

import chisel3._
import chisel3.util._
import mboom.core.backend.decode.MicroALUOp
import mboom.core.backend.rename.{BusyTableExtQueryPort, BusyTableQueryPort}
import mboom.core.backend.utils._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush

class AluIssueQueue(volume: Int, detectWidth: Int) extends Module {
  assert(volume >= detectWidth + 2)
  private val enqNum = 2

  private val deqNum = 2


  val io = IO(new Bundle {
    val enq     = Vec(enqNum, Flipped(DecoupledIO(new MicroALUOp)))
    val bt      = Vec(detectWidth+2, Flipped(new BusyTableQueryPort))
    val btExt   = Vec(1, Flipped(new BusyTableExtQueryPort))
    val waken   = Vec((detectWidth+2)*2, Flipped(new AluIssueAwakeReadPort))

    val data    = Vec(detectWidth, Output(Valid(new MicroALUOp)))
    val opReady = Vec(detectWidth, Output(new IssueReady))
    val issue   = Vec(deqNum, Input(Valid(UInt(log2Ceil(detectWidth).W))))

    val robHead = Input(UInt(robEntryNumWidth.W))
    val potentialStallReg = Input(UInt(phyRegAddrWidth.W))

    val flush   = Input(new ExecFlush(nBranchCount))
  })

  // val issueQ = Module(new Queue(new MicroALUOp, entries = volume - detectWidth, hasFlush = true))

  val ram      = RegInit(0.U.asTypeOf(Vec(volume, new MicroALUOp)))
  val ramNext  = WireInit(ram)

  val ramOpReady = RegInit(0.U.asTypeOf(Vec(detectWidth, new IssueReady)))
  val ramOpReadyNext = WireInit(ramOpReady)

  val entryNum = RegInit(0.U((log2Ceil(volume) + 1).W))

  val numDeqed = PopCount(io.issue.map(_.valid))
  val numAfterDeq = entryNum - numDeqed

  val numEnqed = PopCount(io.enq.map(_.fire))

  // valid & ready
  for (i <- 0 until enqNum) {
    io.enq(i).ready := (entryNum + i.U) < volume.U
  }
  for (i <- 0 until detectWidth) {
    io.data(i).valid := i.U < entryNum
    io.data(i).bits := ram(i)
    io.opReady(i)   := ramOpReady(i)
  }

  val opReady = Wire(Vec(detectWidth+2, new IssueReady))

  for (i <- 0 until detectWidth+2) {
    io.bt(i).op1Addr := ram(i).baseOp.rsAddr
    io.bt(i).op2Addr := ram(i).baseOp.rtAddr

    io.waken(2*i).addr   := ram(i).baseOp.rsAddr
    io.waken(2*i+1).addr := ram(i).baseOp.rtAddr

    opReady(i).op1Rdy :=
      ram(i).baseOp.op1.valid ||
      !io.bt(i).op1Busy ||
      io.waken(2*i).awaken
    opReady(i).op2Rdy :=
      ram(i).baseOp.op2.valid ||
      !io.bt(i).op2Busy ||
      io.waken(2*i+1).awaken
//    opReady(i).robDiffer :=
//      ram(i).baseOp.robAddr - io.robHead
    opReady(i).potentialStall :=
      (io.potentialStallReg === ram(i).baseOp.rsAddr && !ram(i).baseOp.op1.valid) ||
        (io.potentialStallReg === ram(i).baseOp.rtAddr && !ram(i).baseOp.op2.valid) ||
        (io.potentialStallReg === ram(i).baseOp.extAddr && !ram(i).baseOp.opExt.valid)

    if (i == 0) {
      io.btExt(i).extAddr := ram(i).baseOp.extAddr
      val extOpReady = ram(i).baseOp.opExt.valid || !io.btExt(i).extBusy
      opReady(i).opExtRdy := extOpReady
    } else {
      val extOpReady = ram(i).baseOp.opExt.valid
      opReady(i).opExtRdy := extOpReady
    }
  }

  val enqOpReady = Wire(Vec(enqNum, new IssueReady))
  for (i <- 0 until enqNum) {
    enqOpReady(i).op1Rdy   := io.enq(i).bits.baseOp.op1.valid
    enqOpReady(i).op2Rdy   := io.enq(i).bits.baseOp.op2.valid
    enqOpReady(i).opExtRdy := io.enq(i).bits.baseOp.opExt.valid
    // enqOpReady(i).robDiffer := 63.U
    enqOpReady(i).potentialStall := 1.B
  }

  /////////////////////////////////////////////////////

  val offset = Wire(Vec(volume, UInt(log2Ceil(volume).W)))
  for (i <- 0 until volume) {
    when((i + 1).U >= io.issue(1).bits && io.issue(1).valid) {
      offset(i) := 2.U
    }.elsewhen(i.U >= io.issue(0).bits && io.issue(0).valid) {
      offset(i) := 1.U
    }.otherwise {
      offset(i) := 0.U
    }
  }

  for (i <- 0 until volume) {
    ramNext(i) := MuxCase(ram(i), Seq(
      (i.U === numAfterDeq)       -> io.enq(0).bits,
      (i.U === numAfterDeq + 1.U) -> io.enq(1).bits,
      (offset(i) === 1.U)         -> ram((i + 1) % volume),
      (offset(i) === 2.U)         -> ram((i + 2) % volume)
    ))
  }

  for (i <- 0 until detectWidth) {
    ramOpReadyNext(i) := MuxCase(opReady(i), Seq(
      (i.U === numAfterDeq)       -> enqOpReady(0),
      (i.U === numAfterDeq + 1.U) -> enqOpReady(1),
      (offset(i) === 1.U)         -> opReady(i + 1),
      (offset(i) === 2.U)         -> opReady(i + 2)
    ))
  }

  val flushNum = WireInit(0.U((log2Ceil(volume) + 1).W))
  for (i <- 0 until volume) {
    when (i.U < entryNum && !((io.flush.brMask & ram(i).baseOp.brMask).orR)) {
      flushNum := (i+1).U
    }
  }

  entryNum := MuxCase(entryNum - numDeqed + numEnqed, Seq(
    io.flush.extFlush   -> 0.U,
    io.flush.brMissPred -> flushNum
  ))
  ram        := ramNext
  ramOpReady := ramOpReadyNext
}
