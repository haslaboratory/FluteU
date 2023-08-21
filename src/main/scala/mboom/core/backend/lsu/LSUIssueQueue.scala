package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{nBranchCount, phyRegAddrWidth, robEntryNumWidth}
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroLSUOp
import mboom.core.backend.rename.{BusyTableExtQueryPort, BusyTableQueryPort}
import mboom.core.backend.utils.IssueReady

class LSUIssueQueue(volume: Int, detectWidth: Int) extends Module {
  private val enqNum = 1
  private val deqNum = 1

  val io = IO(new Bundle {
    val enq = Vec(enqNum, Flipped(DecoupledIO(new MicroLSUOp)))
    val bt  = Vec(detectWidth+2, Flipped(new BusyTableQueryPort))
    val btExt = Vec(1, Flipped(new BusyTableExtQueryPort))

    val data    = Vec(detectWidth, Valid(new MicroLSUOp))
    val opReady = Vec(detectWidth, Output(new IssueReady))
    val issue   = Vec(deqNum, Flipped(Valid(UInt(log2Ceil(detectWidth).W))))

    val robHead = Input(UInt(robEntryNumWidth.W))
    val potentialStallReg = Input(UInt(phyRegAddrWidth.W))

    val flush   = Input(new ExecFlush(nBranchCount))
  })

  val ram      = RegInit(0.U.asTypeOf(Vec(volume, new MicroLSUOp)))
  val ramNext  = WireInit(ram)

  val ramOpReady     = RegInit(0.U.asTypeOf(Vec(detectWidth, new IssueReady)))
  val ramOpReadyNext = WireInit(ramOpReady)

  val entryNum = RegInit(0.U((log2Ceil(volume)+1).W))

  val numDeqed    = PopCount(io.issue.map(_.valid))
  val numAfterDeq = entryNum - numDeqed

  val numEnqed = PopCount(io.enq.map(_.fire))

  // valid & ready
  for (i <- 0 until enqNum) {
    io.enq(i).ready := (entryNum + i.U) < volume.U
  }
  for (i <- 0 until detectWidth) {
    io.data(i).valid := i.U < entryNum
    io.data(i).bits  := ram(i)
    io.opReady(i)    := ramOpReady(i)
  }

  val opReady = Wire(Vec(detectWidth+1, new IssueReady))
  for (i <- 0 until detectWidth+1) {
    io.bt(i).op1Addr := ram(i).baseOp.rsAddr
    io.bt(i).op2Addr := ram(i).baseOp.rtAddr

    opReady(i).op1Rdy   := ram(i).baseOp.op1.valid || !io.bt(i).op1Busy
    opReady(i).op2Rdy   := ram(i).baseOp.op2.valid || !io.bt(i).op2Busy
    // opReady(i).robDiffer := ram(i).baseOp.robAddr - io.robHead
    opReady(i).potentialStall :=
      (io.potentialStallReg === ram(i).baseOp.rsAddr && !ram(i).baseOp.op1.valid) ||
        (io.potentialStallReg === ram(i).baseOp.rtAddr && !ram(i).baseOp.op2.valid) ||
        (io.potentialStallReg === ram(i).baseOp.extAddr && !ram(i).baseOp.opExt.valid)
    if (i == 0) {
      io.btExt(i).extAddr := ram(i).baseOp.extAddr
      opReady(i).opExtRdy :=
        ram(i).baseOp.opExt.valid || !io.btExt(i).extBusy
    } else {
      opReady(i).opExtRdy := ram(i).baseOp.opExt.valid
    }
  }

  val enqOpReady = Wire(Vec(enqNum, new IssueReady))
  for (i <- 0 until enqNum) {
    io.bt(detectWidth+1+i).op1Addr := io.enq(i).bits.baseOp.rsAddr
    io.bt(detectWidth+1+i).op2Addr := io.enq(i).bits.baseOp.rtAddr

    enqOpReady(i).op1Rdy   := io.enq(i).bits.baseOp.op1.valid ||
      !io.bt(detectWidth+1+i).op1Busy
    enqOpReady(i).op2Rdy   := io.enq(i).bits.baseOp.op2.valid ||
      !io.bt(detectWidth+1+i).op2Busy
    enqOpReady(i).opExtRdy := io.enq(i).bits.baseOp.opExt.valid
    // enqOpReady(i).robDiffer := 63.U
    enqOpReady(i).potentialStall := 1.B
  }

  val offset = Wire(Vec(volume, UInt(log2Ceil(volume).W)))
  for (i <- 0 until volume) {
    when (i.U >= io.issue(0).bits && io.issue(0).valid) {
      offset(i) := 1.U
    } .otherwise {
      offset(i) := 0.U
    }
  }

  for (i <- 0 until volume) {
    ramNext(i) := MuxCase(ram(i), Seq(
      (i.U === numAfterDeq) -> io.enq(0).bits,
      (offset(i) === 1.U)   -> ram((i + 1) % volume)
    ))
  }

  for (i <- 0 until detectWidth) {
    ramOpReadyNext(i) := MuxCase(opReady(i), Seq(
      (i.U === numAfterDeq) -> enqOpReady(0),
      (offset(i) === 1.U)   -> opReady(i+1)
    ))
  }

  val flushNum = WireInit(0.U((log2Ceil(volume) + 1).W))
  for (i <- 0 until volume) {
    when(i.U < entryNum && !((io.flush.brMask & ram(i).baseOp.brMask).orR)) {
      flushNum := (i + 1).U
    }
  }

  entryNum := MuxCase(entryNum - numDeqed + numEnqed, Seq(
    io.flush.extFlush -> 0.U,
    io.flush.brMissPred -> flushNum
  ))
  ram := ramNext
  ramOpReady := ramOpReadyNext
}
