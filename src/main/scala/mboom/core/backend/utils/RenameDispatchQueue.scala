package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.rename.RenameOp
import mboom.core.backend.decode.MicroOp

class DispatchFlush extends Bundle {
  val extFlush   = Bool()
  val brMissPred = Bool()
  val brOriginalMask  = UInt((nBranchCount+1).W)
}

// process sync
class RenameDispatchQueue(val nWays: Int, val capacity: Int = 4) extends Module {
  assert(nWays == 2)
  val io = IO(new Bundle {
    val enq       = Vec(nWays, Flipped(Decoupled(new RenameOp)))
    val deq       = Vec(nWays, Decoupled(new RenameOp))

    val robValid0 = Input(Bool())
    val robValid1 = Input(Bool())

    val flush     = Input(new DispatchFlush)

  })

  val ram    = RegInit(0.U.asTypeOf(Vec(capacity, new RenameOp)))
  val head   = RegInit(0.U(log2Ceil(capacity).W))
  val tail   = RegInit(0.U(log2Ceil(capacity).W))
  val number = RegInit(0.U(log2Ceil(capacity+1).W))

  val robValid0 = RegInit(0.B)
  val robValid1 = RegInit(0.B)

  robValid0 := io.robValid0 || io.deq(0).fire
  robValid1 := io.robValid1 || io.deq(0).fire

  for (i <- 0 until nWays) {
    val offset = i.U
    io.enq(i).ready := (number + offset) < capacity.U
    when (io.enq(i).fire) {
      ram((tail + offset)(log2Up(capacity)-1, 0)) := io.enq(i).bits
    }
  }

  val mask = WireInit(VecInit(1.B, 1.B))

  val headSync = ram(head).uop.sync && (
    robValid1 || (!ram(head).uop.inSlot && robValid0))

  when (ram(head + 1.U).uop.sync) {
    mask(1) := 0.B
  }.elsewhen(headSync) {
    mask(1) := 0.B
  }

  when (headSync) {
    mask(0) := 0.B
  }

  for (i <- 0 until nWays) {
    val offset = i.U
    io.deq(i).valid := (number > offset) && mask(i) && !io.flush.brMissPred
    io.deq(i).bits  := ram(head + offset)
  }

  val numEnqed = PopCount(io.enq.map(_.fire))
  val numDeqed = PopCount(io.deq.map(_.fire))

  val reserveHead = ((io.flush.brOriginalMask & ram(head).rename.brMask).orR) && (number =/= 0.U)

  when (io.flush.extFlush) {
    head   := 0.U
    tail   := 0.U
    number := 0.U
  } .elsewhen(io.flush.brMissPred) {
    when (reserveHead) {
      tail   := head + 1.U
      number := 1.U
    } .otherwise {
      tail   := head
      number := 0.U
    }
  }.otherwise {
    head   := head + numDeqed
    tail   := tail + numEnqed
    number := number - numDeqed + numEnqed
  }

}
