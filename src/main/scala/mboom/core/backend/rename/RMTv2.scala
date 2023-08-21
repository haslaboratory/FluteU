package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

class RenameFlush(val nBrCount: Int) extends Bundle {
  val extFlush  = Bool()
  val brRestore = Bool()
  val brTag     = UInt(log2Ceil(nBrCount).W)
  val brMask    = UInt((nBrCount+1).W)
}

class RMTv2(numWays: Int, numCommit: Int, nBrCount: Int = 4) extends Module {
  private val nPregs = phyRegAmount
  private val wPregs = log2Ceil(nPregs)

  val io = IO(new Bundle {
    // 每条指令需要三个读端口 0->dest;1->src1;2->src2
    val read = Vec(numWays, Vec(3, new RMTReadPort))

    // 每条指令需要一个写端口
    val write = Vec(numWays, new RMTWritePort)

    // branch
    val brRequest = Input(Bool())
    val brMask    = Output(UInt((nBrCount + 1).W))
    val brTag     = Valid(UInt(log2Ceil(nBrCount).W))

    // commit to aRAT
    val commit = new RMTCommit(numCommit)

    val flush = Input(new RenameFlush(nBrCount))
  })
  val sRAT = RegInit(VecInit(
    for (i <- 0 until archRegAmount) yield {
      i.U(phyRegAddrWidth.W)
    }
  ))
  val aRAT = RegInit(VecInit(
    for (i <- 0 until archRegAmount) yield {
      i.U(phyRegAddrWidth.W)
    }
  ))

  // sRAT
  for (i <- 0 until numWays; tp <- 0 until 3) {
    val archRegAddr = io.read(i)(tp).addr
    val phyRegAddr = sRAT(archRegAddr)
    io.read(i)(tp).data := phyRegAddr
  }

  val nextSRat = WireInit(sRAT)
  for (i <- 0 until numWays) {
    val en = io.write(i).en
    val archRegAddr = io.write(i).addr
    val phyRegAddr = io.write(i).data
    when(en) {
      nextSRat(archRegAddr) := phyRegAddr
    }
  }

  // aRAT
  val nextARat = WireInit(aRAT)
  for (i <- 0 until numCommit) {
    val en = io.commit.write(i).en
    val archRegAddr = io.commit.write(i).addr
    val phyRegAddr = io.commit.write(i).data
    when(en) {
      nextARat(archRegAddr) := phyRegAddr
    }
  }
  aRAT := nextARat

  // checkpoints
  val cRATs = RegInit(0.U.asTypeOf(Vec(nBrCount, Vec(archRegAmount, UInt(phyRegAddrWidth.W)))))
  // branch
  val brHead = RegInit(0.U(log2Ceil(nBrCount).W)) // deq
  val brTail = RegInit(0.U(log2Ceil(nBrCount).W)) // enq
  val brNum  = RegInit(0.U(log2Ceil(nBrCount + 1).W)) // number
  val brMask = RegInit(1.U((nBrCount + 1).W))

  when (io.brRequest) {
    cRATs(brTail) := nextSRat
  }

  // next sRAT
  sRAT := MuxCase(nextSRat, Seq(
    io.flush.extFlush  -> aRAT,
    io.flush.brRestore -> cRATs(io.flush.brTag)
  ))

  // branch
  io.brMask      := brMask
  io.brTag.valid := brNum < nBrCount.U
  io.brTag.bits  := brTail

  val flush = io.flush.extFlush

  val brCommit  = io.commit.brCommit
  val brRequest = io.brRequest && !io.flush.brRestore

  brTail := MuxCase(brTail, Seq(
    flush              -> 0.U,
    io.flush.brRestore -> (io.flush.brTag + 1.U),
    brRequest          -> (brTail + 1.U)
  ))
  brHead := MuxCase(brHead, Seq(
    flush     -> 0.U,
    brCommit  -> (brHead + 1.U),
  ))

  brMask := MuxCase(brMask, Seq(
    flush              -> 1.U((nBrCount + 1).W),
    io.flush.brRestore -> io.flush.brMask.do_rotateLeft(1),
    brRequest          -> brMask.do_rotateLeft(1),
  ))

  val reserveBrNum =
    Mux(
      (1.U + io.flush.brTag - brHead) =/= 0.U,
      1.U + io.flush.brTag - brHead,
      4.U,
    )

  val flushBrNum =
    Mux(brCommit,
      io.flush.brTag - brHead,
      reserveBrNum,
    )

  brNum := MuxCase(brNum, Seq(
    flush                    -> 0.U,
    io.flush.brRestore       -> flushBrNum,
    (brRequest && !brCommit) -> (brNum + 1.U),
    (!brRequest && brCommit) -> (brNum - 1.U),
  ))

}
