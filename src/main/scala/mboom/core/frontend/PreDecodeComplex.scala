package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.cache.ICacheResp1
import mboom.config.BasicInstructions
import mboom.config.CPUConfig._
import mboom.core.frontend.BPU.{BPUComplexResponse, BPUParam}
import mboom.util.BitPatCombine

class PreDecodeComplex extends Module {
  assert(fetchGroupSize == 2)
  private val nWays = fetchGroupSize
  val io = IO(new Bundle {
    // information
    val resValid   = Input(Bool())
    val bpu        = Input(new BPUComplexResponse(BPUParam(nWays)))
    val inst       = Input(new ICacheResp1)
    // redirect
    val innerFlush  = Output(Bool())
    val redirAddr   = Output(UInt(addrWidth.W))
    val redirInSlot = Output(Bool())
    val redirDsAddr = Output(UInt(addrWidth.W))
    // output
    val outValid   = Output(Vec(fetchGroupSize, Bool()))
    val outPc      = Output(Vec(fetchGroupSize, UInt(addrWidth.W)))
    val outInst    = Output(Vec(fetchGroupSize, UInt(instrWidth.W)))
    val outTarget  = Output(Vec(fetchGroupSize, UInt(addrWidth.W)))
    val outTaken   = Output(Vec(fetchGroupSize, Bool()))
    val outInSlot  = Output(Vec(fetchGroupSize, Bool()))
    // flush
    val extFlush   = Input(Bool())
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

  // val valid0 = io.bpu.pc(2) === 0.U
  val begin = io.bpu.pc(2)
  val pcBase = io.bpu.pc & "hfffffff8".U(32.W)

  val nlpTaken = Wire(Vec(nWays, Bool()))
  nlpTaken(0) := io.bpu.nlp.taken(0)
  nlpTaken(1) := io.bpu.nlp.taken(1)
  val nlpBta   = io.bpu.nlp.bta

  val immTarget = Wire(Vec(nWays, UInt(addrWidth.W)))
  immTarget(0) := Cat((pcBase + 4.U)(31, 28), io.inst.data(0)(25, 0), 0.U(2.W))
  immTarget(1) := Cat((pcBase + 8.U)(31, 28), io.inst.data(1)(25, 0), 0.U(2.W))

  val needUpd  = Wire(Vec(nWays, Bool()))
  val isBranch = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    needUpd(i)  := Inst.needUpdate(io.inst.data(i))
    isBranch(i) := Inst.isBranch(io.inst.data(i))
  }

  // calculate taken
  val taken = Wire(Vec(nWays, Bool()))
  taken(0) := (needUpd(0) || (isBranch(0) && io.bpu.taken(0)))
  taken(1) := (needUpd(1) || (isBranch(1) && io.bpu.taken(1)))

  // calculate target
  val target = Wire(Vec(nWays, UInt(addrWidth.W)))
  target(0) := MuxCase(pcBase + 8.U,Seq(
    needUpd(0)                       -> immTarget(0),
    (isBranch(0) && io.bpu.taken(0)) -> io.bpu.bta(0)
  ))
  target(1) := MuxCase(pcBase + 12.U,Seq(
    needUpd(1)                       -> immTarget(1),
    (isBranch(1) && io.bpu.taken(1)) -> io.bpu.bta(1)
  ))

  // calcalate update
  val missPred = Wire(Vec(nWays, Bool()))
  missPred(0) := (begin === 0.U) &&
    ((taken(0) =/= nlpTaken(0)) || (nlpTaken(0) && nlpBta(0) =/= target(0)))
  missPred(1) := (begin === 1.U || !nlpTaken(0)) &&
    ((taken(1) =/= nlpTaken(1)) || (nlpTaken(1) && nlpBta(1) =/= target(1)))

  val innerFlush = missPred.reduce(_ || _)
  val sel = Mux(
    !missPred(0) || (!taken(0) && taken(1)),
    1.U,
    0.U,
  )

  // redirect
  val outInnerFlush = RegNext(io.resValid && innerFlush && !fetchDelaySlot && !io.extFlush, 0.B)
  val outRedirAddr  = RegNext(Mux(sel === 0.U, target(0), pcBase + 8.U), 0.U)
  val outRedirInSlot = RegNext((sel === 1.U) && taken(1), 0.B)
  val outRedirDsAddr = RegNext(target(1), 0.U)

  io.innerFlush  := outInnerFlush
  io.redirAddr   := outRedirAddr
  io.redirInSlot := outRedirInSlot
  io.redirDsAddr := outRedirDsAddr

  // output
  for (i <- 0 until nWays) {
    if (i == 0) {
      io.outValid(i) := io.resValid && (begin === 0.U)
    } else {
      io.outValid(i) := io.resValid && !fetchDelaySlot
    }
//    io.outValid(i)  := io.resValid &&
//      ((fetchDelaySlot && i.U === begin) || (!fetchDelaySlot && i.U >= begin))
    io.outPc(i)     := Cat(io.bpu.pc(31, 3), i.U(1.W), io.bpu.pc(1, 0)) // pcBase + (4 * i).U
    io.outInst(i)   := io.inst.data(i)
    io.outTaken(i)  := taken(i)
    io.outTarget(i) := target(i)
    if (i == 0) {
      io.outInSlot(i) := delaySlot
    } else {
      io.outInSlot(i) := isBranch(i - 1)
    }
  }

  // delaySlot
  when (io.extFlush) {
    delaySlot := 0.B
  } .elsewhen (io.resValid && !fetchDelaySlot) {
    delaySlot := isBranch(1)
  } .elsewhen(io.resValid && fetchDelaySlot) {
    delaySlot := 0.B
  }

  // fetchDelaySlot
  when (io.extFlush) {
    fetchDelaySlot := 0.B
  } .elsewhen (io.resValid && !fetchDelaySlot) {
    fetchDelaySlot := taken(1)
  }.elsewhen(io.resValid && fetchDelaySlot) {
    fetchDelaySlot := 0.B
  }

}
