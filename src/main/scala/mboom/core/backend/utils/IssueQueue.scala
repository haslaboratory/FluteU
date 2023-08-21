package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.components.SinglePortQueue
import mboom.core.backend.decode.MicroUnitOp
import mboom.core.backend.rename.BusyTableQueryPort
import mboom.config.CPUConfig._

class IssueReady extends Bundle {
  val op1Rdy   = Bool()
  val op2Rdy   = Bool()
  val opExtRdy = Bool()
  // val robDiffer = UInt(robEntryNumWidth.W)
  val potentialStall = Bool()
}

object IssueReady {
//  def apply(op1Rdy: Bool, op2Rdy: Bool): IssueReady = {
//    val entry = Wire(new IssueReady)
//    entry.op1Rdy := op1Rdy
//    entry.op2Rdy := op2Rdy
//    entry
//  }

  def apply(op1Rdy: Bool, op2Rdy: Bool, opExtRdy: Bool): IssueReady = {
    val entry = Wire(new IssueReady)
    entry.op1Rdy   := op1Rdy
    entry.op2Rdy   := op2Rdy
    entry.opExtRdy := opExtRdy
    entry.potentialStall := 0.B
    entry
  }
}
class IssueQueue[T <: MicroUnitOp](gen: T, size: Int, hasFlush: Boolean = true) extends Module {
  val io = IO(new Bundle{
    val enq   = Flipped(DecoupledIO(gen))
    val bt    = Vec(3, Flipped(new BusyTableQueryPort))
    val deq   = DecoupledIO(gen)
    val opReady = Output(new IssueReady)
    val flush = if (hasFlush) Some(Input(Bool())) else None
  })

  val q           = Module(new SinglePortQueue(gen, entries = size-1, hasFlush = hasFlush))
  val qHead       = RegInit(0.U.asTypeOf(Valid(gen)))
  val qIssueReady = RegInit(0.U.asTypeOf(new IssueReady))

  // bt
  io.bt(0).op1Addr  := qHead.bits.baseOp.rsAddr
  io.bt(0).op2Addr  := qHead.bits.baseOp.rtAddr
  io.bt(1).op1Addr  := q.io.deq.bits.baseOp.rsAddr
  io.bt(1).op2Addr  := q.io.deq.bits.baseOp.rtAddr
  io.bt(2).op1Addr  := io.enq.bits.baseOp.rsAddr
  io.bt(2).op2Addr  := io.enq.bits.baseOp.rtAddr

  val qHeadIssueReady = IssueReady(
    qHead.bits.baseOp.op1.valid || !io.bt(0).op1Busy,
    qHead.bits.baseOp.op2.valid || !io.bt(0).op2Busy,
    qHead.bits.baseOp.opExt.valid
  )
  val qDeqIssueReady  = IssueReady(
    q.io.deq.bits.baseOp.op1.valid || !io.bt(1).op1Busy,
    q.io.deq.bits.baseOp.op2.valid || !io.bt(1).op2Busy,
    q.io.deq.bits.baseOp.opExt.valid
  )
  val qEnqIssueReady  = IssueReady(
    io.enq.bits.baseOp.op1.valid || !io.bt(2).op1Busy,
    io.enq.bits.baseOp.op2.valid || !io.bt(2).op2Busy,
    io.enq.bits.baseOp.opExt.valid
  )

  // enq
  val enqHead = (io.enq.valid && !qHead.valid) ||
    (io.enq.valid && q.io.count === 0.U && io.deq.fire)

  io.enq.ready   := q.io.enq.ready

  q.io.enq.bits  := io.enq.bits
  q.io.enq.valid := io.enq.valid && !enqHead

  // deq
  io.deq.valid   := qHead.valid
  io.deq.bits    := qHead.bits
  io.opReady     := qIssueReady

  q.io.deq.ready := io.deq.ready

  // flush
  q.io.flush.get := io.flush.get
  when (io.flush.get) {
    qHead       := 0.U.asTypeOf(Valid(gen))
    qIssueReady := 0.U.asTypeOf(new IssueReady)
  } .otherwise {
    qHead.valid := MuxCase(qHead.valid, Seq(
      enqHead     -> 1.B,
      io.deq.fire -> q.io.deq.valid
    ))
    qHead.bits  := MuxCase(qHead.bits , Seq(
      enqHead     -> io.enq.bits,
      io.deq.fire -> q.io.deq.bits
    ))
    qIssueReady := MuxCase(qHeadIssueReady, Seq(
      enqHead     -> qEnqIssueReady,
      io.deq.fire -> qDeqIssueReady
    ))
  }


}
