package mboom.core.backend.utils
import chisel3._
import chisel3.util._
class CompressIssueQueue[T <: Data](entryType: T, volume: Int, detectWidth: Int) extends Module {
  private val enqNum = 2

  private val deqNum = 2

  val io = IO(new Bundle {
    val enq   = Vec(enqNum, Flipped(DecoupledIO(entryType)))
    val data  = Vec(detectWidth, Output(Valid(entryType)))
    val issue = Vec(deqNum, Input(Valid(UInt(log2Ceil(detectWidth).W))))

    val flush = Input(Bool())
  })

  val ram      = Reg(Vec(volume, entryType))
  val entryNum = RegInit(0.U((log2Ceil(volume)+1).W))

  val ramNext = WireInit(ram)

  val numDeqed = PopCount(io.issue.map(_.valid))
  val numAfterDeq = entryNum - numDeqed

  val numEnqed = PopCount(io.enq.map(_.fire))

  // valid & ready
  for (i <- 0 until enqNum) {
    io.enq(i).ready := (entryNum + i.U) < volume.U
  }
  for (i <- 0 until detectWidth) {
    io.data(i).valid := i.U < entryNum
    io.data(i).bits  := ram(i)
  }

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
      (offset(i) === 1.U)         -> ram((i+1) % volume),
      (offset(i) === 2.U)         -> ram((i+2) % volume)
    ))
  }

  entryNum := Mux(io.flush, 0.U, entryNum - numDeqed + numEnqed)
  ram      := ramNext

}