package mboom.core.backend.decode

import Chisel.Cat
import chisel3._
import chisel3.util.{Fill, MuxCase, MuxLookup, log2Ceil}
import mboom.config.CPUConfig._
import mboom.core.components._
import mboom.core.frontend.IBEntryNew
import mboom.config.Instructions._
import mboom.core.frontend.BPU.BranchType
import mboom.core.frontend.BPU.component.RASEntry

class OpBundle extends Bundle {
  val op    = UInt(dataWidth.W)
  val valid = Bool()
}

class MicroBaseOp extends Bundle {
  private val regWidth = phyRegAddrWidth
  val regWriteEn   = Bool()
  val op1          = new OpBundle()
  val op2          = new OpBundle()
  val opExt        = new OpBundle()
  val writeRegAddr = UInt(regWidth.W)

  val rsAddr       = UInt(regWidth.W)
  val rtAddr       = UInt(regWidth.W)
  val extAddr      = UInt(regWidth.W)

  val pc           = UInt(instrWidth.W)
  val robAddr      = UInt(robEntryNumWidth.W)

  // branch
  val brMask       = UInt((nBranchCount+1).W)
}

abstract class MicroUnitOp extends Bundle {
  val baseOp = new MicroBaseOp
}

class MicroALUOp extends MicroUnitOp {
  val aluOp        = UInt(ALUOp.width.W)
  val bjCond       = UInt(BJCond.width.W)
  val immediate    = UInt(dataWidth.W)
  val immeJumpAddr = UInt(addrWidth.W)
  val predictBT    = UInt(addrWidth.W)

  val tlblRfl      = Bool()
  val tlblInv      = Bool()
  val syscall      = Bool()
  val break        = Bool()
  val eret         = Bool()
  val reservedI    = Bool()
}

class MicroLSUOp extends MicroUnitOp {
  val loadMode   = UInt(LoadMode.width.W)
  val storeMode  = UInt(StoreMode.width.W)
  val vAddr      = UInt(dataWidth.W)

  val pfn        = UInt(20.W)
  val uncache    = Bool()
  val tlbRfl     = Bool()
  val tlbInv     = Bool()
  val tlbMod     = Bool()
}

class MicroMDUOp extends MicroUnitOp {
  val mduOp        = UInt(MDUOp.width.W)

  val cacheOp      = UInt(CacheOp.width.W)
  val immediate    = UInt(dataWidth.W)
  val cp0RegAddr   = UInt(5.W)
  val cp0RegSel    = UInt(3.W)
}

class MicroBRUOp extends MicroUnitOp {
  val bjCond       = UInt(BJCond.width.W)
  val immediate    = UInt(dataWidth.W)
  val immeJumpAddr = UInt(addrWidth.W)
  val predictBT    = UInt(addrWidth.W)

  val brAlloc      = Bool()
  val brTag        = UInt(log2Ceil(nBranchCount).W)
}
class MicroOp(rename: Boolean = false) extends Bundle {
  private val regWidth = if (rename) phyRegAddrWidth else regAddrWidth

  val regWriteEn = Bool()
  val loadMode   = UInt(LoadMode.width.W)
  val storeMode  = UInt(StoreMode.width.W)
  val aluOp      = UInt(ALUOp.width.W)
  val mduOp      = UInt(MDUOp.width.W)
  val op1        = new OpBundle()
  val op2        = new OpBundle()
  val opExt      = new OpBundle()
  val bjCond     = UInt(BJCond.width.W)
  val instrType  = UInt(InstrType.width.W)

  val writeRegAddr = UInt(regWidth.W)
  val immediate    = UInt(dataWidth.W)
  val immeJumpAddr = UInt(addrWidth.W)

  // for issue wake up
  val rsAddr = UInt(regWidth.W)
  val rtAddr = UInt(regWidth.W)
  // calculate branchAddr in Ex
  val pc      = UInt(instrWidth.W)
  val robAddr = UInt(robEntryNumWidth.W)
  // store branch prediction
  val predictBT = UInt(addrWidth.W)
  val inSlot    = Bool()
  // for branch flush
  val brType    = UInt(BranchType.width.W)
  val brCheck   = Bool()
  // for sync
  val sync      = Bool()

  val cp0RegAddr = UInt(5.W)
  val cp0RegSel  = UInt(3.W)

  val cacheOp    = UInt(2.W)

  val tlblRfl    = Bool()
  val tlblInv    = Bool()
  val syscall    = Bool()
  val break      = Bool()
  val eret       = Bool()
  val reservedI  = Bool()

  val instruction = UInt(32.W)
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val instr = Input(new IBEntryNew)
    // 气泡流水 decoder不读regfile
    // val withRegfile = Flipped(new RegFileReadIO)
    val microOp = Output(new MicroOp)

    val syncIn  = Input(Bool())
    val syncOut = Output(Bool())
  })
  val controller = Module(new Controller)

  // 解开 Fetch 传来的 IBEntry 结构
  // pc没对齐给空指令
  val instruction = Wire(UInt(instrWidth.W))
  instruction   := Mux(io.instr.addr(1, 0) === 0.U, io.instr.inst, 0.U(dataWidth.W))
  io.microOp.pc := io.instr.addr

  io.microOp.predictBT := io.instr.predictBT
  io.microOp.inSlot    := io.instr.inSlot

  // Immediate ////////////////////////////////////////////////////
  val extendedImm = WireInit(0.U(dataWidth.W))
  extendedImm := MuxLookup(
    key = controller.io.immRecipe,
    default = 0.U(dataWidth.W),
    mapping = Seq(
      ImmRecipe.sExt -> Cat(Fill(16, instruction(15)), instruction(15, 0)),
      ImmRecipe.uExt -> Cat(0.U(16.W), instruction(15, 0)),
      ImmRecipe.lui  -> Cat(instruction(15, 0), 0.U(16.W)),
    )
  )
  io.microOp.immediate := extendedImm
  val pcplusfour = io.instr.addr + 4.U
  io.microOp.immeJumpAddr := Cat(pcplusfour(31, 28), instruction(25, 0), 0.U(2.W))
  /////////////////////////////////////////////////////////////////

  // Controller //////////////////////////////////////////////////////
  controller.io.instruction := instruction
  val writeArfRegAddr = MuxLookup(
    key = controller.io.regDst,
    default = instruction(15, 11),
    mapping = Seq(
      RegDst.rt    -> instruction(20, 16),
      RegDst.rd    -> instruction(15, 11),
      RegDst.GPR31 -> 31.U(regAddrWidth.W)
    )
  )
  io.microOp.regWriteEn := controller.io.regWriteEn && writeArfRegAddr =/= 0.U
  io.microOp.loadMode   := controller.io.loadMode
  io.microOp.storeMode  := controller.io.storeMode
  io.microOp.aluOp      := controller.io.aluOp
  io.microOp.mduOp      := Mux(controller.io.mduOp === MDUOp.ri, MDUOp.none, controller.io.mduOp)
  io.microOp.op1.op := MuxLookup(
    key = controller.io.op1Recipe,
    default = 0.U,
    mapping = Seq(
      Op1Recipe.rs      -> 0.U,
      Op1Recipe.pcPlus8 -> (io.instr.addr + 8.U),
      Op1Recipe.shamt   -> instruction(10, 6),
      Op1Recipe.zero    -> 0.U
    )
  )
  io.microOp.op1.valid := Mux(controller.io.op1Recipe === Op1Recipe.rs, 0.B, 1.B)
  io.microOp.op2.op := MuxLookup(
    key = controller.io.op2Recipe,
    default = 0.U,
    mapping = Seq(
      Op2Recipe.rt   -> 0.U,
      Op2Recipe.imm  -> extendedImm,
      Op2Recipe.zero -> 0.U
    )
  )
  io.microOp.op2.valid := Mux(controller.io.op2Recipe === Op2Recipe.rt, 0.B, 1.B)
  io.microOp.opExt.op := MuxLookup(
    key = controller.io.opExtRecipe,
    default = 0.U,
    mapping = Seq(
      OpExtRecipe.rd   -> 0.U,
      OpExtRecipe.zero -> 0.U
    )
  )
  io.microOp.opExt.valid := Mux(controller.io.opExtRecipe === OpExtRecipe.rd, 0.B, 1.B)
  io.microOp.bjCond    := controller.io.bjCond
  io.microOp.brType  := MuxCase(BranchType.Branch, Seq(
    (controller.io.bjCond === BJCond.none)    -> BranchType.None,
    (controller.io.bjCond === BJCond.jal)     -> BranchType.DirectCall,
    (controller.io.bjCond === BJCond.jr &&
      instruction(25, 21) === 31.U(5.W))      -> BranchType.FuncReturn,
    (controller.io.bjCond === BJCond.jr &&
      instruction(25, 21) =/= 31.U(5.W))      -> BranchType.IndirectJump,
    (controller.io.bjCond === BJCond.jalr)    -> BranchType.IndirectCall,
    (controller.io.bjCond === BJCond.j)       -> BranchType.DirectJump,
    (controller.io.bjCond === BJCond.bgezal ||
      controller.io.bjCond === BJCond.bltzal) -> BranchType.BranchCall
  ))
  // TODO: need update
  io.microOp.brCheck := 0.B //controller.io.bjCond =/= BJCond.none

  // sync instruction
  val sync =
    (controller.io.mduOp === MDUOp.mtc0) ||
    (controller.io.mduOp === MDUOp.tlbr) ||
    (controller.io.mduOp === MDUOp.tlbp) ||
    (controller.io.mduOp === MDUOp.tlbwi) ||
    (controller.io.mduOp === MDUOp.tlbwr) ||
    (controller.io.mduOp === MDUOp.cache)
  io.microOp.sync := sync || io.syncIn
  io.syncOut      := sync

  io.microOp.instrType := controller.io.instrType
  ////////////////////////////////////////////////////////////////////

  // RegFile /////////////////////////////////////////////////////////
  io.microOp.writeRegAddr := writeArfRegAddr
  // Issue Wake Up
  io.microOp.rsAddr := instruction(25, 21)
  io.microOp.rtAddr := instruction(20, 16)
  /////////////////////////////////////////////////////////////////

  io.microOp.robAddr := DontCare

  val cp0RegAddr = instruction(15, 11)
  val cp0RegSel  = instruction(2, 0)

  io.microOp.cp0RegAddr := cp0RegAddr
  io.microOp.cp0RegSel  := cp0RegSel

  io.microOp.cacheOp    := instruction(17, 16)

  io.microOp.tlblInv    := io.instr.tlblInv
  io.microOp.tlblRfl    := io.instr.tlblRfl
  io.microOp.syscall    := instruction === SYSCALL
  io.microOp.break      := instruction === BREAK
  io.microOp.reservedI  := controller.io.mduOp === MDUOp.ri
  io.microOp.eret       := instruction === ERET

  io.microOp.instruction := instruction
}