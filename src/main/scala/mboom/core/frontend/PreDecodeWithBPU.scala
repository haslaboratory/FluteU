package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.cache.{ICacheResp, ICacheResp1}
import mboom.config.CPUConfig._
import mboom.config.BasicInstructions
import mboom.util.BitPatCombine

class PreDecoderOutputWithBPU extends Bundle {
  val innerFlush = Bool()
  val recover_addr = UInt(addrWidth.W)
  val recover_push = Bool()
  val target     = UInt(addrWidth.W)
  val outValid   = Vec(fetchGroupSize+1, Bool())
  val predictBT  = Vec(fetchGroupSize, UInt(addrWidth.W))
  val isBranch   = Vec(fetchGroupSize, Bool())
}

class PreDecodeOutputWithCache extends Bundle {
  val innerFlush = Bool()
  val bta        = UInt(addrWidth.W)
  val outValid   = Vec(fetchGroupSize+1, Bool())
  val predictBT  = Vec(fetchGroupSize, UInt(addrWidth.W))
  val taken      = Vec(fetchGroupSize, Bool())
  val inSlot     = Vec(fetchGroupSize+1, Bool())
}
class PreDecodeWithCache extends Module {
  assert(fetchGroupSize == 2)
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
//        SYSCALL,
//        TEQ,
//        TEQI,
//        TGE,
//        TGEU,
//        TGEI,
//        TGEIU
      )
    )
  }

  val io = IO(new Bundle{
    val resultValid = Input(Bool())
    val flush       = Input(Bool()) // extFlush
    val instruction = Flipped(Valid(new ICacheResp1))
    val pcEntry     = Input(new PcQueueEntryWithCache)
    val out         = Output(new PreDecodeOutputWithCache)
  })

  val delaySlot     = RegInit(0.B)
  val trueDelaySlot = RegInit(0.B)

  val pcplusfour   = io.pcEntry.pc + 4.U
  val pcpluseight  = io.pcEntry.pc + 8.U
  val cacheValid = io.instruction.bits.valid.map(v => v && io.resultValid)
  val needUpd = io.instruction.bits.data.map(d => Inst.needUpdate(d))
  val isBranch = io.instruction.bits.data.map(d => Inst.isBranch(d))

  val innerFlush = WireInit(0.B)
  val bta        = WireInit(0.U(32.W))
  val predictBT  = WireInit(io.pcEntry.bta)
  val taken      = WireInit(io.pcEntry.taken)

  val outValidExt  = WireInit(io.pcEntry.taken(1))
  val delaySlotExt = WireInit(1.B)

  // 当前只处理比较简单的情况，由于有延迟槽和cache行的介入，处理所有情况会相当复杂。
  // 修正发现不应跳转但跳转了的情况，直接 innerFlush
  // 修正发现应跳转但没跳转的情况，只处理能拿到延迟槽的情况
  val target0 = Cat(pcplusfour(31, 28), io.instruction.bits.data(0)(25, 0), 0.U(2.W))
  val target1 = Cat(pcpluseight(31, 28), io.instruction.bits.data(1)(25, 0), 0.U(2.W))

  when (needUpd(0) && !io.pcEntry.taken(0) && cacheValid(1)) {
    // 0 T F (1 1 0)
    innerFlush   := 1.B
    bta          := target0
    predictBT(0) := target0
    taken(0)     := 1.B

    taken(1)     := 0.B
    delaySlotExt := 0.B
    outValidExt  := 0.B
  } .elsewhen (!isBranch(0) && io.pcEntry.taken(0)) {
    // 0 F T (1 0 0) / (1 1 0)
    innerFlush   := 1.B
    bta          := Mux(cacheValid(1), pcpluseight, pcplusfour)
    predictBT(0) := pcpluseight
    taken(0)     := 0.B
    taken(1)     := 0.B

    delaySlotExt := 0.B
    outValidExt  := 0.B
  } .elsewhen (needUpd(1) && !io.pcEntry.taken(1) && cacheValid(2)) {
    // 1 T F (1 1 1)
    innerFlush   := 1.B
    bta          := target1
    predictBT(1) := target1
    taken(0)     := 0.B
    taken(1)     := 1.B
    // update outValid
    delaySlotExt := 0.B
    outValidExt  := 1.B
  } .elsewhen (!isBranch(1) && !io.pcEntry.taken(0) && io.pcEntry.taken(1)) {
    // 1 F T (1 1 0)
    innerFlush   := 1.B
    bta          := pcpluseight
    predictBT(1) := pcplusfour + 8.U
    taken(1)     := 0.B
    // update outValid
    delaySlotExt := 0.B
    outValidExt  := 0.B
  } .elsewhen(io.pcEntry.taken(0) && io.pcEntry.taken(1)) {
    taken(1)    := 0.B
    delaySlotExt := 0.B
    outValidExt := 0.B
  }

  when (io.flush) {
    delaySlot := 0.B
  } .elsewhen(io.resultValid && !delaySlot) {
    delaySlot := io.pcEntry.delaySlot && delaySlotExt
  } .elsewhen(io.resultValid && delaySlot) {
    delaySlot := 0.B
  }

  when (io.flush) {
    trueDelaySlot := 0.B
  } .elsewhen (io.resultValid) {
    trueDelaySlot := MuxCase(isBranch(0), Seq(
      io.out.outValid(2) -> isBranch(2),
      io.out.outValid(1) -> isBranch(1)
    ))
  }

  // correct bpu predict branch
  for (i <- 0 until fetchGroupSize) {
    when(!taken(i)) {
      predictBT(i) := io.pcEntry.pc + 8.U + (4 * i).U
    }
  }

  io.out.outValid(0) := cacheValid(0) && io.resultValid && !io.flush
  io.out.outValid(1) := !delaySlot && cacheValid(1) && io.resultValid && !io.flush
  io.out.outValid(2) := !delaySlot && outValidExt && cacheValid(2) && io.resultValid && !io.flush
  io.out.inSlot(0)   := trueDelaySlot
  io.out.inSlot(1)   := isBranch(0)
  io.out.inSlot(2)   := isBranch(1)
  io.out.predictBT   := Mux(delaySlot, VecInit(io.pcEntry.pc+8.U, 0.U), predictBT)
  io.out.taken       := Mux(delaySlot, 0.U.asTypeOf(Vec(fetchGroupSize, Bool())), taken)


  val outInnerFlush = RegNext(!delaySlot && innerFlush && io.resultValid && !io.flush, 0.B)
  val outBta        = RegNext(bta, 0.U)

  io.out.innerFlush := outInnerFlush
  io.out.bta        := outBta
}
