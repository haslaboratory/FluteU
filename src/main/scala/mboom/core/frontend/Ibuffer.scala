package mboom.core.frontend

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.isPow2
import chisel3.util.log2Up
import chisel3.util.PopCount
import chisel3.util.MixedVec

// 16 2 2
// gen = IBEntry
class IbufferBundle[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int) extends Bundle {
  val read  = Vec(numRead, Decoupled(gen)) // 输入为
  val write = Flipped(Vec(numWrite, Decoupled(gen)))
  val space = Output(UInt((log2Up(numEntries) + 1).W))
  val flush = Input(Bool())
}

/**
 * Ibuffer 是特化过的FIFO；
 * 写端口ready信号不依赖valid, 不允许 valid 位 [0 1 1 0] 模式。只允许 [1 1 0 0] (1置前)
 * 读端口valid信号不依赖ready, 只与寄存器状态相关。只允许 ready 位 [1 1 0] (1置前)
 */ 
class Ibuffer[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int) extends Module {
  assert(isPow2(numEntries) && numEntries > 1)

  val io = IO(new IbufferBundle(gen, numEntries, numRead, numWrite))

  val data = RegInit(VecInit(Seq.fill(numEntries)(0.U.asTypeOf(gen))))

  // tail是数据入队的位置（该位置目前没数据），head是数据出队的第一个位置（该位置放了最老的数据）
  val head_ptr = RegInit(0.U(log2Up(numEntries).W))
  val tail_ptr = RegInit(0.U(log2Up(numEntries).W))

  // 为了区分空队列和满队列满，队列不允许真正全部放满（numEntries=8时，有8个位置，但同一时刻最多只能用7个）
  val difference = tail_ptr - head_ptr
  val deqEntries = difference
  val enqEntries = (numEntries - 1).U - difference
  // val maxvec = Wire(MixedVec(1 to 10) map {i => UInt(i.W)})
  io.space := enqEntries

  // Popcount 用来统计其中的1数目
  val numTryEnq = PopCount(io.write.map(_.valid))
  val numTryDeq = PopCount(io.read.map(_.ready))
  
  // 最大允许出队和入队的数目
  val numDeq    = Mux(deqEntries < numTryDeq, deqEntries, numTryDeq)
  val numEnq    = Mux(enqEntries < numTryEnq, enqEntries, numTryEnq)

  for (i <- 0 until numWrite) {
    val offset = i.U
    when(offset < enqEntries) {
      data((tail_ptr + offset)(log2Up(numEntries) - 1, 0)) := io.write(i).bits
    }
    io.write(i).ready := offset < enqEntries
  }

  for (i <- 0 until numRead) {
    val offset = i.U
    io.read(i).bits  := data((head_ptr + offset)(log2Up(numEntries) - 1, 0))
    io.read(i).valid := offset < deqEntries//  && !io.flush
  }

  head_ptr := Mux(io.flush, 0.U, head_ptr + numDeq)
  tail_ptr := Mux(io.flush, 0.U, tail_ptr + numEnq)
}
