package mboom.core.backend.lsu.component

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.nBranchCount
import mboom.core.backend.ExecFlush
import mboom.config.CPUConfig._

// compress queue
class UncacheBuffer(size: Int = unbufferAmount) extends Module {
  assert(isPow2(size) && size > 1)
  val width = log2Ceil(size)
  val io = IO(new Bundle {
    val write   = new SbufferWrite
    val retire  = Input(Bool())
    val head    = Decoupled(new SbufferEntry)

    val num     = Output(UInt(log2Ceil(unbufferAmount).W))
    val flush   = Input(new ExecFlush(nBranchCount))
  })

  val entries    = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(new SbufferEntry))))
  val headPtr    = RegInit(0.U(width.W)) // deq
  val tailPtr    = RegInit(0.U(width.W)) // enq
  val retirePtr  = RegInit(0.U(width.W)) // commit

  val curNumber = tailPtr - headPtr

  // write
  val hasRoom = curNumber < (size-1).U
  io.write.ready := (curNumber < (size-2).U)

  when (hasRoom && io.write.valid) {
    entries(tailPtr) := SbUtils.writePort2Entry(WireInit(io.write))
  }

  // flush
  val flushWrite = ((io.flush.brMask & io.write.brMask).orR) || !io.write.valid

  when (io.flush.extFlush) {
    tailPtr := retirePtr
  } .elsewhen(io.flush.brMissPred && flushWrite) {
    tailPtr := SbUtils.getValidTail(io.flush.brMask, entries, retirePtr, tailPtr)
  } .elsewhen(hasRoom && io.write.valid) {
    tailPtr := tailPtr + 1.U
  }

  // retire
  when (io.retire) {
    retirePtr := retirePtr + 1.U
  }

  // head
  io.head.valid := headPtr =/= retirePtr
  io.head.bits  := entries(headPtr)
  when (io.head.fire) {
    headPtr := headPtr + 1.U
  }

  io.num := curNumber
}
