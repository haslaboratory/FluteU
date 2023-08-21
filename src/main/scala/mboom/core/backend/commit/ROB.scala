package mboom.core.backend.commit

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.cp0.ExceptionBundle
import mboom.util.ValidBundle
import mboom.core.frontend.BPU.BranchType
import mboom.core.backend.decode.StoreMode
import mboom.core.backend.decode.MDUOp
import mboom.core.frontend.BPU.component.RASEntry

// TODO: simplify
class ROBEntry extends Bundle {
  val pc        = UInt(addrWidth.W)
  val complete  = Bool()
  val logicReg  = UInt(archRegAddrWidth.W)
  val physicReg = UInt(phyRegAddrWidth.W)
  val originReg = UInt(phyRegAddrWidth.W)
  val exception = new ExceptionBundle
  val instrType = UInt(instrTypeWidth.W)
  val regWEn    = Bool()
  val memWMode  = UInt(StoreMode.width.W)
  val unCache   = Bool()

  // branch
  val computeBT   = UInt(addrWidth.W)
  val branchFail  = Bool()
  val branchTaken = Bool()
  val inSlot      = Bool()
  val brType      = UInt(BranchType.width.W)
  val brMask      = UInt((nBranchCount+1).W)
  val brCheck     = Bool()
  val brAlloc     = Bool()

  // hi, lo, cp0; valid for enable
  val hiRegWrite  = ValidBundle(UInt(32.W))
  val loRegWrite  = ValidBundle(UInt(32.W))
  val badvaddr    = UInt(addrWidth.W)
  val eret        = Bool()

  // debug
  val instruction = UInt(32.W)
}

class ROBWrite(numEntries: Int) extends Bundle {
  val bits    = Input(new ROBEntry)
  val valid   = Input(Bool())
  val ready   = Output(Bool())
  val robAddr = Output(UInt(log2Up(numEntries).W))
}
/// once complete bundle valid, complete shall be set automatically
class ROBCompleteBundle(robAddrWidth: Int = robEntryNumWidth) extends Bundle {
  val valid     = Bool()
  val robAddr   = UInt(robAddrWidth.W)
  val exception = new ExceptionBundle

  // branch
  val computeBT   = UInt(addrWidth.W)
  val branchFail  = Bool()
  val branchTaken = Bool()

  // hi, lo, cp0
  val hiRegWrite  = ValidBundle(UInt(32.W))
  val loRegWrite  = ValidBundle(UInt(32.W))
  val badvaddr    = UInt(addrWidth.W)

  // write
  val unCache     = Bool()

}

class ROBFlush extends Bundle {
  val extFlush   = Bool()
  val brMissPred = Bool()
  val brRobAddr  = UInt(robEntryNumWidth.W)
}

class ROB(numEntries: Int, numRead: Int, numWrite: Int, numSetComplete: Int) extends Module {
  assert(isPow2(numEntries) && numEntries > 1)

  val io = IO(new Bundle {
    val head        = Output(UInt(log2Up(numEntries).W))
    val read        = Vec(numRead, Decoupled(new ROBEntry))
    val write       = Vec(numWrite, new ROBWrite(numEntries))
    val setComplete = Vec(numSetComplete, Input(new ROBCompleteBundle(log2Up(numEntries))))

    // branch restore
//    val flush        = Input(Bool())
//    val branchFlush  = Input(Bool())
//    val brMask       = Input(UInt((nBranchCount+1).W))
    val flush        = Input(new ROBFlush)

  })

  val entries = RegInit(0.U.asTypeOf(Vec(numEntries, new ROBEntry)))

  // tail是数据入队的位置（该位置目前没数据），head是数据出队的第一个位置（该位置放了最老的数据）
  val head_ptr = RegInit(0.U(log2Up(numEntries).W))
  val tail_ptr = RegInit(0.U(log2Up(numEntries).W))

  // 为了区分空队列和满队列满，队列不允许真正全部放满（numEntries=8时，有8个位置，但同一时刻最多只能用7个）
  val difference = tail_ptr - head_ptr
  val deqEntries = difference
  val enqEntries = (numEntries - 1).U - difference

//  val numTryEnq = PopCount(io.write.map(_.valid))
//  val numTryDeq = PopCount(io.read.map(_.ready))
//  val numDeq    = Mux(deqEntries < numTryDeq, deqEntries, numTryDeq)
//  val numEnq    = Mux(enqEntries < numTryEnq, enqEntries, numTryEnq)

  val numEnq    = PopCount(io.write.map(x => x.ready && x.valid))
  val numDeq    = PopCount(io.read.map(x => x.ready && x.valid))

  io.head := head_ptr

  for (i <- 0 until numRead) {
    when(io.read(i).fire) {
      printf(p"commit: ${Hexadecimal(io.read(i).bits.pc)}\n")
    }
  }

  // Assumptions:
  // 读写端口的均需要把1集中放前面
  // 类似[1, 0, 1]的读写行为是未定义的
  for (i <- 0 until numWrite) {
    val offset = i.U
    when(io.write(i).valid && io.write(i).ready) {
      entries((tail_ptr + offset)(log2Up(numEntries) - 1, 0)) := io.write(i).bits
    }
    io.write(i).ready   := offset < enqEntries
    io.write(i).robAddr := (tail_ptr + offset)(log2Up(numEntries) - 1, 0)
  }

  for (i <- 0 until numRead) {
    val offset = i.U

    io.read(i).bits  := entries((head_ptr + offset)(log2Up(numEntries) - 1, 0))
    io.read(i).valid := offset < deqEntries
  }

  val flushTail = Mux(io.flush.brRobAddr+1.U === tail_ptr, io.flush.brRobAddr+1.U, io.flush.brRobAddr+2.U)

  head_ptr := Mux(io.flush.extFlush, 0.U, head_ptr + numDeq)
  tail_ptr := MuxCase(tail_ptr + numEnq, Seq(
    io.flush.extFlush   -> 0.U,
    io.flush.brMissPred -> flushTail
  ))

  for (port <- io.setComplete) {
    when(port.valid) {
      entries(port.robAddr).complete  := 1.B
      entries(port.robAddr).exception := port.exception
      entries(port.robAddr).branchTaken := port.branchTaken
      entries(port.robAddr).computeBT   := port.computeBT
      entries(port.robAddr).branchFail  := port.branchFail
      entries(port.robAddr).hiRegWrite  := port.hiRegWrite
      entries(port.robAddr).loRegWrite  := port.loRegWrite
      entries(port.robAddr).badvaddr    := port.badvaddr
      entries(port.robAddr).unCache     := port.unCache
    }
  }
}
