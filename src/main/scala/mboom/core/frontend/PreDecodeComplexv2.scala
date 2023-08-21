package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.cache.ICacheResp1
import mboom.core.frontend.BPU.BPUComplexv2Response
import mboom.config.BasicInstructions
import mboom.config.CPUConfig._
import mboom.util.BitPatCombine

class PreDecodeComplexv2 extends Module {
  private val nWays = 4
  val io = IO(new Bundle {
    // information
    val resValid    = Input(Bool())
    val bpu         = Input(new BPUComplexv2Response(nWays))
    val inst        = Input(new ICacheResp1)
    // redirect
    val innerFlush  = Output(Bool())
    val redirAddr   = Output(UInt(addrWidth.W))
    val redirInSlot = Output(Bool())
    val redirDsAddr = Output(UInt(addrWidth.W))
    // output
    val outValid    = Output(Vec(nWays, Bool()))
    val outPc       = Output(Vec(nWays, UInt(addrWidth.W)))
    val outInst     = Output(Vec(nWays, UInt(instrWidth.W)))
    val outTarget   = Output(Vec(nWays, UInt(addrWidth.W)))
    val outTaken    = Output(Vec(nWays, Bool()))
    val outInSlot   = Output(Vec(nWays, Bool()))
    // flush
    val extFlush    = Input(Bool())
  })

  private object Inst extends BasicInstructions {
    def needUpdate = BitPatCombine(Seq(J, JAL)) // 判断是进行无条件跳转

    def isBranch = BitPatCombine( // 需要等停数据
      Seq(
        BEQ,
        BGEZ,
        BGEZAL,
        BGTZ,
        BLEZ,
        BLTZ,
        BLTZAL,
        BNE,
        J,
        JAL,
        JALR,
        JR,
        BEQL
      )
    )
  }

  val delaySlot      = RegInit(0.B)
  val fetchDelaySlot = RegInit(0.B)

  val begin = io.bpu.pc(3, 2)
  val pc   = io.bpu.pc & "hfffffff0".U(32.W)

  val needUpd  = Wire(Vec(nWays, Bool()))
  val isBranch = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    needUpd(i) := Inst.needUpdate(io.inst.data(i))
    isBranch(i) := Inst.isBranch(io.inst.data(i))
  }

  // calculate taken
  val taken = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    taken(i) :=
      (i.U >= begin) && (needUpd(i) || (isBranch(i) && io.bpu.taken(i)))
  }

  val immTarget = Wire(Vec(nWays, UInt(addrWidth.W)))
  for (i <- 0 until nWays) {
    val pcPlusFour = pc + (4 + 4 * i).U
    immTarget(i) := Cat(pcPlusFour(31, 28), io.inst.data(i)(25, 0), 0.U(2.W))
  }
  val target = Wire(Vec(nWays, UInt(addrWidth.W)))
  for (i <- 0 until nWays) {
    val pcNext = pc + (8 + 4 * i).U
    target(i) := MuxCase(pcNext,Seq(
      needUpd(i)                       -> immTarget(i),
      (isBranch(i) && io.bpu.taken(i)) -> io.bpu.bta(i)
    ))
  }

  val innerFlush = taken.reduce(_ || _)
  val sel = WireInit((nWays-1).U(log2Ceil(nWays).W))
  for (i <- nWays-1 to 0 by -1) {
    when (taken(i)) {
      sel := i.U
    }
  }

  io.innerFlush  := io.resValid && innerFlush && !fetchDelaySlot
  io.redirAddr   := Mux(sel.andR, pc + 16.U, target(sel))
  io.redirInSlot := sel.andR
  io.redirDsAddr := target(3)

  // outValid
  val selExt = Cat(0.U(1.W), sel)
  for (i <- 0 until nWays) {
    io.outValid(i)  := io.resValid && (
      (!fetchDelaySlot && (i.U >= begin) && (i.U <= selExt + 1.U)) ||
        (fetchDelaySlot && i.U === begin)
      )
    io.outPc(i)     := pc + (4 * i).U
    io.outInst(i)   := io.inst.data(i)
    io.outTaken(i)  := taken(i)
    io.outTarget(i) := target(i)
    if (i == 0) {
      io.outInSlot(i) := delaySlot
    } else {
      io.outInSlot(i) := isBranch(i-1)
    }
  }

  // delaySlot
  when(io.extFlush) {
    delaySlot := 0.B
  }.elsewhen(io.resValid && !fetchDelaySlot) {
    delaySlot := isBranch(3) && sel.andR
  }.elsewhen(io.resValid && fetchDelaySlot) {
    delaySlot := 0.B
  }

  // fetchDelaySlot
  when(io.extFlush) {
    fetchDelaySlot := 0.B
  }.elsewhen(io.resValid && !fetchDelaySlot) {
    fetchDelaySlot := innerFlush && sel.andR
  }.elsewhen(io.resValid && fetchDelaySlot) {
    fetchDelaySlot := 0.B
  }
}
