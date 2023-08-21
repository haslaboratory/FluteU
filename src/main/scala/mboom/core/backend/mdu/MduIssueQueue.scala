package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{nBranchCount, phyRegAddrWidth, robEntryNumWidth}
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroMDUOp
import mboom.core.backend.rename.BusyTableQueryPort
import mboom.core.backend.utils.IssueReady

class MduIssueQueue(volume: Int) extends Module {
  private val enqNum = 1

  val io = IO(new Bundle {
    val enq     = Vec(enqNum, Flipped(DecoupledIO(new MicroMDUOp)))
    val bt      = Vec(3, Flipped(new BusyTableQueryPort))
    val deq     = DecoupledIO(new MicroMDUOp)
    val opReady = Output(new IssueReady)

    val robHead = Input(UInt(robEntryNumWidth.W))
    val potentialStallReg = Input(UInt(phyRegAddrWidth.W))

    val flush   = Input(new ExecFlush(nBranchCount))
  })

  val ram     = RegInit(0.U.asTypeOf(Vec(volume, new MicroMDUOp)))
  val ramNext = WireInit(ram)

  val ramOpReady = RegInit(0.U.asTypeOf(new IssueReady))
  val ramOpReadyNext = WireInit(ramOpReady)

  val entryNum = RegInit(0.U((log2Ceil(volume) + 1).W))

  val numDeqed = io.deq.fire.asUInt
  val numAfterDeq = entryNum - numDeqed

  val numEnqed = PopCount(io.enq.map(_.fire))

  for (i <- 0 until enqNum) {
    io.enq(i).ready := (entryNum + i.U) < volume.U
  }

  io.deq.bits  := ram(0)
  io.deq.valid := 0.U < entryNum
  io.opReady   := ramOpReady

  val opReady = Wire(Vec(2, new IssueReady))
  for (i <- 0 until 2) {
    io.bt(i).op1Addr := ram(i).baseOp.rsAddr
    io.bt(i).op2Addr := ram(i).baseOp.rtAddr

    opReady(i).op1Rdy := ram(i).baseOp.op1.valid || !io.bt(i).op1Busy
    opReady(i).op2Rdy := ram(i).baseOp.op2.valid || !io.bt(i).op2Busy
    opReady(i).opExtRdy := ram(i).baseOp.opExt.valid
    // opReady(i).robDiffer := ram(i).baseOp.robAddr - io.robHead
    opReady(i).potentialStall :=
      (io.potentialStallReg === ram(i).baseOp.rsAddr && !ram(i).baseOp.op1.valid) ||
        (io.potentialStallReg === ram(i).baseOp.rtAddr && !ram(i).baseOp.op2.valid)
  }

  val enqOpReady = Wire(Vec(enqNum, new IssueReady))
  for (i <- 0 until enqNum) {
    io.bt(i+2).op1Addr := io.enq(i).bits.baseOp.rsAddr
    io.bt(i+2).op2Addr := io.enq(i).bits.baseOp.rtAddr

    enqOpReady(i).op1Rdy := io.enq(i).bits.baseOp.op1.valid || !io.bt(i+2).op1Busy
    enqOpReady(i).op2Rdy := io.enq(i).bits.baseOp.op2.valid || !io.bt(i+2).op2Busy
    enqOpReady(i).opExtRdy := io.enq(i).bits.baseOp.opExt.valid
    // enqOpReady(i).robDiffer := 63.U
    enqOpReady(i).potentialStall := 1.B
  }

  for (i <- 0 until volume) {
    ramNext(i) := MuxCase(ram(i), Seq(
      (i.U === numAfterDeq)       -> io.enq(0).bits,
      io.deq.fire                 -> ram((i + 1) % volume)
    ))
  }
  ram := ramNext

  ramOpReadyNext := MuxCase(opReady(0), Seq(
    (numAfterDeq === 0.U) -> enqOpReady(0),
    io.deq.fire           -> opReady(1)
  ))

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
