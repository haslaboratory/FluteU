package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._


class FreelistCommit(val nCommit: Int) extends Bundle {
  val free  = Input(Vec(nCommit, Valid(UInt(phyRegAddrWidth.W))))
}
class Freelist(val nWays: Int, val nCommit: Int) extends Module {
  assert(nWays == 2)
  assert(nCommit == 2)
  private val nPregs = phyRegAmount
  private val pregsIW = log2Ceil(nPregs)

  val io = IO(new Bundle {
    val deqCount = Input(UInt((nWays+1).W))

    val allocPregs = Output(Vec(nWays, Valid(UInt(pregsIW.W))))

    // commit to arch Freelist: alloc for aFreelist; free for both aFreelist & sFreelist
    val commit = new FreelistCommit(nCommit)

    val chToArch = Input(Bool())

  })

  val sFreelist = RegInit(VecInit(
    for (i <- 0 until nPregs) yield {
      i.U(pregsIW.W)
    }
  ))
  // deq
  val sDeqPtr  = RegInit(32.U(pregsIW.W))
  // enq
  val sEnqPtr = RegInit(0.U(pregsIW.W))

  val sNumber = sEnqPtr - sDeqPtr

  val deqCount = io.deqCount

  for (i <- 0 until nWays) {
    io.allocPregs(i).valid := i.U < sNumber
    io.allocPregs(i).bits  := sFreelist(sDeqPtr+i.U)
  }

  val enqFires = io.commit.free.map(_.valid)
  val enqCount = PopCount(enqFires)
  val enqPregs = io.commit.free.map(_.bits)
  for (i <- 0 until nCommit) {
    val offset = WireInit(0.U(log2Ceil(nCommit).W))
    for (j <- 0 until i) {
      when (enqFires(j)) {
        offset := 1.U
      }
    }
    when (enqFires(i)) {
      sFreelist(sEnqPtr + offset) := enqPregs(i)
    }
  }

  sEnqPtr := sEnqPtr + enqCount
  when (io.chToArch) {
    // fix: hard code
    sDeqPtr := sEnqPtr + enqCount - 32.U(pregsIW.W)
  } .otherwise {
    sDeqPtr := sDeqPtr + deqCount
  }
}
