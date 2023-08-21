package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.util.ValidBundle

class HILORead extends Bundle {
  val hi = UInt(dataWidth.W)
  val lo = UInt(dataWidth.W)
}

class HILOWrite extends Bundle {
  val hi = ValidBundle(UInt(dataWidth.W))
  val lo = ValidBundle(UInt(dataWidth.W))
}

// 无写后读冲突

class HILOv1 extends Module {
  val io = IO(new Bundle() {
    val read   = Output(new HILORead)
    val write  = Input(new HILOWrite)
    val commit = Input(new HILOWrite)

    val flush  = Input(new ExecFlush(nBranchCount))
  })

  val rHi = RegInit(0.U(32.W))
  val rLo = RegInit(0.U(32.W))

  val rHin = Mux(io.write.hi.valid, io.write.hi.bits, rHi)
  val rLon = Mux(io.write.lo.valid, io.write.lo.bits, rLo)

  val cHi = RegInit(0.U(32.W))
  val cLo = RegInit(0.U(32.W))

  val cHin = Mux(io.commit.hi.valid, io.commit.hi.bits, cHi)
  val cLon = Mux(io.commit.lo.valid, io.commit.lo.bits, cLo)

  io.read.hi := rHin
  io.read.lo := rLon

  rHi := Mux(io.flush.extFlush, cHin, rHin)
  rLo := Mux(io.flush.extFlush, cLon, rLon)
  cHi := cHin
  cLo := cLon

}

class HILOWritev2 extends Bundle {
  val hi     = ValidBundle(UInt(dataWidth.W))
  val lo     = ValidBundle(UInt(dataWidth.W))
  val brMask = UInt((nBranchCount+1).W)
}

class HILOv2 extends Module {
  val io = IO(new Bundle {
    val canWrite = Output(Bool())

    val read   = Output(new HILORead)
    val write  = Input(new HILOWritev2)
    val commit = Input(new HILOWrite)

    val flush  = Input(new ExecFlush(nBranchCount))
  })

  val hiQueue      = RegInit(0.U.asTypeOf(Vec(hiloCheckpointSize, UInt(dataWidth.W))))
  val loQueue      = RegInit(0.U.asTypeOf(Vec(hiloCheckpointSize, UInt(dataWidth.W))))
  val brMaskQueue  = RegInit(0.U.asTypeOf(Vec(hiloCheckpointSize, UInt((nBranchCount+1).W))))

  val hi = RegInit(0.U(dataWidth.W))
  val lo = RegInit(0.U(dataWidth.W))

  val cHi = RegInit(0.U(dataWidth.W))
  val cLo = RegInit(0.U(dataWidth.W))

  val headPtr = RegInit(0.U(log2Ceil(hiloCheckpointSize).W)) // deq
  val tailPtr = RegInit(0.U(log2Ceil(hiloCheckpointSize).W)) // enq

  // difference
  val difference = tailPtr - headPtr

  // canWrite
  io.canWrite := difference < (hiloCheckpointSize-2).U

  // read / write
  val writeValid = io.write.hi.valid || io.write.lo.valid

  val hiWrite = Mux(io.write.hi.valid, io.write.hi.bits, hi)
  val loWrite = Mux(io.write.lo.valid, io.write.lo.bits, lo)

  when (writeValid) {
    hiQueue(tailPtr)     := hiWrite
    loQueue(tailPtr)     := loWrite
    brMaskQueue(tailPtr) := io.write.brMask
  }

  io.read.hi := hiWrite
  io.read.lo := loWrite
  // commit
  val commitValid = io.commit.hi.valid || io.commit.lo.valid

  val hiCommit = Mux(io.commit.hi.valid, io.commit.hi.bits, cHi)
  val loCommit = Mux(io.commit.lo.valid, io.commit.lo.bits, cLo)
  cHi := hiCommit
  cLo := loCommit

  // flush
  val flushWrite = (io.flush.brMask & io.write.brMask).orR || !writeValid
  val validTailPtr = WireInit(headPtr)

  for (i <- 0 until hiloCheckpointSize) {
    val index = headPtr + i.U
    when (i.U < difference && !(io.flush.brMask & brMaskQueue(index)).orR) {
      validTailPtr := index
    }
  }
  // head tail hi lo
  when (io.flush.extFlush) {
    headPtr := 0.U
    tailPtr := 0.U
    hi      := cHi
    lo      := cLo
  } .otherwise {
    // head
    when (io.commit.hi.valid || io.commit.lo.valid) {
      headPtr := headPtr + 1.U
    }
    // tail
    when (io.flush.brMissPred && flushWrite) {
      tailPtr := validTailPtr + 1.U
      hi      := hiQueue(validTailPtr)
      lo      := loQueue(validTailPtr)
    } .elsewhen(io.write.hi.valid || io.write.lo.valid) {
      tailPtr := tailPtr + 1.U
      hi      := hiWrite
      lo      := loWrite
    }
  }
}