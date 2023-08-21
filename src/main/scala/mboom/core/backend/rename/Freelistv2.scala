package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

class Freelistv2(val nWays: Int, val nCommit: Int, val nBrCount: Int) extends Module {
  assert(nWays == 2)
  assert(nCommit == 2)
  private val nPregs = phyRegAmount
  private val pregsIW = log2Ceil(nPregs)

  val io = IO(new Bundle {
    val deqCount = Input(UInt((nWays + 1).W))

    val allocPregs = Output(Vec(nWays, Valid(UInt(pregsIW.W))))

    // commit to arch Freelist: alloc for aFreelist; free for both aFreelist & sFreelist
    val commit = new FreelistCommit(nCommit)

    val flush = Input(new RenameFlush(nBrCount))

    // branch
    val brRequest = Input(Bool())
    val brTag     = Input(UInt(log2Ceil(nBrCount).W))
  })

  val sFreelist = RegInit(VecInit(
    for (i <- 0 until nPregs) yield {
      i.U(pregsIW.W)
    }
  ))
  // deq
  val sDeqPtr = RegInit(32.U(pregsIW.W))
  // enq
  val sEnqPtr = RegInit(0.U(pregsIW.W))

  val sNumber = sEnqPtr - sDeqPtr

  val deqCount = io.deqCount

  for (i <- 0 until nWays) {
    io.allocPregs(i).valid := i.U < sNumber
    io.allocPregs(i).bits := sFreelist(sDeqPtr + i.U)
  }

  val enqFires = io.commit.free.map(_.valid)
  val enqCount = PopCount(enqFires)
  val enqPregs = io.commit.free.map(_.bits)
  for (i <- 0 until nCommit) {
    val offset = WireInit(0.U(log2Ceil(nCommit).W))
    for (j <- 0 until i) {
      when(enqFires(j)) {
        offset := 1.U
      }
    }
    when(enqFires(i)) {
      sFreelist(sEnqPtr + offset) := enqPregs(i)
    }
  }

  sEnqPtr := sEnqPtr + enqCount

  val cDeqPtrs = RegInit(0.U.asTypeOf(Vec(nBrCount, UInt(pregsIW.W))))

  when (io.brRequest) {
    cDeqPtrs(io.brTag) := sDeqPtr + deqCount
  }

  when(io.flush.extFlush) {
    // fix: hard code
    sDeqPtr := sEnqPtr - 32.U(pregsIW.W)
  }.elsewhen(io.flush.brRestore) {
    sDeqPtr := cDeqPtrs(io.flush.brTag)
  }.otherwise {
    sDeqPtr := sDeqPtr + deqCount
  }
}
