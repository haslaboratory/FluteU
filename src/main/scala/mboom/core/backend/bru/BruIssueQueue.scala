package mboom.core.backend.bru

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{nBranchCount, phyRegAddrWidth, robEntryNumWidth}
import mboom.core.backend.ExecFlush
import mboom.core.backend.alu.AluIssueAwakeReadPort
import mboom.core.backend.decode.MicroBRUOp
import mboom.core.backend.rename.{BusyTable, BusyTableQueryPort}
import mboom.core.backend.utils.IssueReady

class BruIssueQueue(volume: Int, detectWidth: Int) extends Module {

  val io = IO(new Bundle {
    val enq     = Flipped(DecoupledIO(new MicroBRUOp))
    val bt      = Vec(detectWidth + 1, Flipped(new BusyTableQueryPort))
    val waken   = Vec((detectWidth + 1) * 2, Flipped(new AluIssueAwakeReadPort))

    val data    = Vec(detectWidth, Valid(new MicroBRUOp))
    val opReady = Vec(detectWidth, Output(new IssueReady))
    val issue   = Flipped(Valid(UInt(log2Ceil(detectWidth).W)))

    val robHead = Input(UInt(robEntryNumWidth.W))
    val potentialStallReg = Input(UInt(phyRegAddrWidth.W))

    val flush   = Input(new ExecFlush(nBranchCount))

  })

  val ram     = RegInit(0.U.asTypeOf(Vec(volume, new MicroBRUOp)))
  val ramNext = WireInit(ram)

  val ramOpReady     = RegInit(0.U.asTypeOf(Vec(detectWidth, new IssueReady)))
  val ramOpReadyNext = WireInit(ramOpReady)

  val entryNum    = RegInit(0.U((log2Ceil(volume) + 1).W))

  val numDeqed    = io.issue.fire.asUInt
  val numAfterDeq = entryNum - numDeqed

  val numEnqed = io.enq.fire.asUInt

  io.enq.ready := entryNum < volume.U

  for (i <- 0 until detectWidth) {
    io.data(i).valid := i.U < entryNum
    io.data(i).bits := ram(i)
    io.opReady(i) := ramOpReady(i)
  }

  val opReady = Wire(Vec(detectWidth+1, new IssueReady))
  for (i <- 0 until detectWidth+1) {
    io.bt(i).op1Addr    := ram(i).baseOp.rsAddr
    io.bt(i).op2Addr    := ram(i).baseOp.rtAddr

    io.waken(2*i).addr  := ram(i).baseOp.rsAddr
    io.waken(2*i+1).addr  := ram(i).baseOp.rtAddr

    opReady(i).op1Rdy   := ram(i).baseOp.op1.valid || !io.bt(i).op1Busy || io.waken(2*i).awaken
    opReady(i).op2Rdy   := ram(i).baseOp.op2.valid || !io.bt(i).op2Busy || io.waken(2*i+1).awaken
    opReady(i).opExtRdy := ram(i).baseOp.opExt.valid
    // opReady(i).robDiffer := ram(i).baseOp.robAddr - io.robHead
    opReady(i).potentialStall :=
      (io.potentialStallReg === ram(i).baseOp.rsAddr && !ram(i).baseOp.op1.valid) ||
        (io.potentialStallReg === ram(i).baseOp.rtAddr && !ram(i).baseOp.op2.valid)
  }

  val enqOpReady = Wire( new IssueReady)

  enqOpReady.op1Rdy   := io.enq.bits.baseOp.op1.valid
  enqOpReady.op2Rdy   := io.enq.bits.baseOp.op2.valid
  enqOpReady.opExtRdy := io.enq.bits.baseOp.opExt.valid
  enqOpReady.potentialStall := 1.B

  val offset = Wire(Vec(volume, UInt(log2Ceil(volume).W)))
  for (i <- 0 until volume) {
    when(i.U >= io.issue.bits && io.issue.valid) {
      offset(i) := 1.U
    }.otherwise {
      offset(i) := 0.U
    }
  }

  for (i <- 0 until volume) {
    ramNext(i) := MuxCase(ram(i), Seq(
      (i.U === numAfterDeq) -> io.enq.bits,
      (offset(i) === 1.U)   -> ram((i + 1) % volume)
    ))
  }

  for (i <- 0 until detectWidth) {
    ramOpReadyNext(i) := MuxCase(opReady(i), Seq(
      (i.U === numAfterDeq) -> enqOpReady,
      (offset(i) === 1.U)   -> opReady(i + 1)
    ))
  }

  val flushNum = WireInit(0.U((log2Ceil(volume) + 1).W))
  for (i <- 0 until volume) {
    when(i.U < entryNum && !((io.flush.brMask & ram(i).baseOp.brMask).orR)) {
      flushNum := (i + 1).U
    }
  }

  entryNum := MuxCase(entryNum - numDeqed + numEnqed, Seq(
    io.flush.extFlush   -> 0.U,
    io.flush.brMissPred -> flushNum
  ))
  ram        := ramNext
  ramOpReady := ramOpReadyNext
}
