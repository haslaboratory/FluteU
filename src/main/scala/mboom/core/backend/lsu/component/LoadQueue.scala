package mboom.core.backend.lsu.component

import chisel3._
import chisel3.util._
import mboom.core.backend.lsu.MemReq

class LoadQueue(size: Int) extends Module {
  assert(isPow2(size) && size > 1)
  val io = IO(new Bundle {
    val lqAddr   = Output(UInt(size.W))
    val enq      = Flipped(Decoupled(new MemReq))
    val deq      = Decoupled(new MemReq)

    val flush = Input(Bool())
  })
}
