package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.decode._
import mboom.core.backend.rename._

// process branch alignment
class DecodeRenameQueue(val capacity: Int = 4) extends Module {
  assert(decodeWay == 2)
  val io = IO(new Bundle {
    val enq   = Vec(decodeWay, Flipped(Decoupled(new MicroOp)))
    val deq   = Vec(decodeWay, Decoupled(new MicroOp))

    val flush = Input(Bool())
  })

  val ram    = RegInit(0.U.asTypeOf(Vec(capacity, new MicroOp)))
  val head   = RegInit(0.U(log2Ceil(capacity).W)) // deq
  val tail   = RegInit(0.U(log2Ceil(capacity).W)) // enq
  val number = RegInit(0.U(log2Ceil(capacity+1).W))

  for (i <- 0 until decodeWay) {
    val offset = i.U
    io.enq(i).ready := (number + offset) < capacity.U
    when (io.enq(i).fire) {
      ram((tail + offset)(log2Up(capacity) - 1, 0)) := io.enq(i).bits
    }
  }
  // 跳转指令和延迟槽需要一起 rename / dispatch
//  val mask = WireInit(VecInit(1.B, 1.B))
//  when (ram(head + 1.U).brCheck) {
//    mask(1) := 0.B
//  }
//
//  when (ram(head).brCheck && number <= 1.U) {
//    mask(0) := 0.B
//  }


  for (i <- 0 until decodeWay) {
    val offset = i.U
    io.deq(i).valid := (number > offset) // && mask(i)
    io.deq(i).bits  := ram(head + offset)
  }

  val numEnqed = PopCount(io.enq.map(_.fire))
  val numDeqed = PopCount(io.deq.map(_.fire))

  when (io.flush) {
    head   := 0.U
    tail   := 0.U
    number := 0.U
  } .otherwise {
    head   := head + numDeqed
    tail   := tail + numEnqed
    number := number - numDeqed + numEnqed
  }

}
