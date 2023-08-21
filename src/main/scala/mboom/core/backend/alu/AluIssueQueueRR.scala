package mboom.core.backend.alu

import chisel3._
import chisel3.util._
import mboom.core.backend.decode._

class AluIssueQueueRR(volume: Int, detectWidth: Int) extends Module {
  private val enqNum = 2

  private val deqNum = 2

  private val entryType = new MicroALUOp

  private val width = entryType.getWidth

  val io = IO(new Bundle {
    val enq   = Vec(enqNum, Flipped(DecoupledIO(entryType)))
    val data  = Vec(detectWidth, Output(Valid(entryType)))
    val issue = Vec(detectWidth, Input(Bool()))
    val flush = Input(Bool())
  })
  val queue = Module(new AluCompressIssueQueueRR(UInt(width.W), volume, detectWidth))

  queue.io.flush := io.flush
  queue.io.issue := io.issue

  for (i <- 0 until enqNum) {
    queue.io.enq(i).valid := io.enq(i).valid
    io.enq(i).ready       := queue.io.enq(i).ready
    queue.io.enq(i).bits  := io.enq(i).bits.asUInt
  }

  for (i <- 0 until detectWidth) {
    io.data(i).valid := queue.io.data(i).valid
    io.data(i).bits  := queue.io.data(i).bits.asTypeOf(entryType)
  }
}


// ALUCompressIssueQueue
// @param volume: 每个队列的容量
class AluCompressIssueQueueRR[T <: Data](entryType: T, volume: Int, detectWidth: Int) extends Module {
  private val enqNum = 2

  private val deqNum = 2

  val io = IO(new Bundle {
    val enq   = Vec(enqNum, Flipped(DecoupledIO(entryType)))
    val data  = Vec(detectWidth, Output(Valid(entryType)))
    val issue = Vec(detectWidth, Input(Bool()))
    val flush = Input(Bool())
  })

  val entryNum = RegInit(0.U((log2Ceil(volume)+1).W))

  val numDeqed    = PopCount(io.issue)
  // printf(p"The num dep ${numDeqed}\n")


  // 使用8个队列进行存储
  val issueQueues = for(i <- 0 until  detectWidth) yield Module(new Queue(entryType, 4, hasFlush = true))

  val index0 = RegInit(0.U((log2Ceil(detectWidth) - 1).W))
  val index1 = RegInit(1.U((log2Ceil(detectWidth) - 1).W))

  when (io.enq(0).valid) {
    index0 := index0 + 1.U
  }
  when (io.enq(1).valid) {
    index1 := index1 + 1.U
  }

  for (i <- 0 until detectWidth/2) {
    issueQueues(i*2).io.enq.valid   := io.enq(0).valid && (i.U === index0)
    issueQueues(i*2).io.enq.bits    := io.enq(0).bits
    issueQueues(i*2+1).io.enq.valid := io.enq(1).valid && (i.U === index1)
    issueQueues(i*2+1).io.enq.bits  := io.enq(1).bits
  }

  io.enq(0).ready := MuxCase(0.B, Seq(
    (0.U === index0) -> issueQueues(0).io.enq.ready,
    (1.U === index0) -> issueQueues(2).io.enq.ready,
    (2.U === index0) -> issueQueues(4).io.enq.ready,
    (3.U === index0) -> issueQueues(6).io.enq.ready,
  ))

  io.enq(1).ready := MuxCase(0.B, Seq(
    (0.U === index1) -> issueQueues(1).io.enq.ready,
    (1.U === index1) -> issueQueues(3).io.enq.ready,
    (2.U === index1) -> issueQueues(5).io.enq.ready,
    (3.U === index1) -> issueQueues(7).io.enq.ready,
  ))


  for (i <- 0 until detectWidth) {
    io.data(i).valid := issueQueues(i).io.deq.valid
    io.data(i).bits :=  issueQueues(i).io.deq.bits
  }

  for (i <- 0 until detectWidth) {
    issueQueues(i).io.deq.ready := io.issue(i)
    issueQueues(i).flush        := io.flush
  }
}