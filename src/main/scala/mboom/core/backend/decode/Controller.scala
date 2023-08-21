package mboom.core.backend.decode

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.config.Instructions.{TLBWR, _}
import mboom.core.components.ALUOp
import mboom.core.frontend.BPU.BranchType

class Controller extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(instrWidth.W))

    val regWriteEn = Output(Bool())
    val loadMode   = Output(UInt(LoadMode.width.W))
    val storeMode  = Output(UInt(StoreMode.width.W))
    val aluOp      = Output(UInt(ALUOp.width.W))
    val mduOp      = Output(UInt(MDUOp.width.W))
    val op1Recipe  = Output(UInt(Op1Recipe.width.W))
    val op2Recipe  = Output(UInt(Op2Recipe.width.W))
    val opExtRecipe = Output(UInt(OpExtRecipe.width.W))
    val bjCond     = Output(UInt(BJCond.width.W))
    val regDst     = Output(UInt(RegDst.width.W))
    val immRecipe  = Output(UInt(ImmRecipe.width.W))
    val instrType  = Output(UInt(InstrType.width.W))
  })

  // @formatter:off
  val signals = ListLookup(io.instruction,
    //   regWriteEn, loadMode,          storeMode,           aluOp,      op1Recipe,         op2Recipe,      bjCond,      regDst        immRecipe       instrType      mduOp      opExtRecipe
    /*default*/
    List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.ri,     OpExtRecipe.zero),
    Array(
      /** Logical Instructions **/
      AND    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.and,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      OR     -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.or,   Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      XOR    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.xor,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      NOR    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.nor,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ANDI   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.and,  Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ORI    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.or,   Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      XORI   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.xor,  Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      LUI    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.or,   Op1Recipe.zero,    Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.lui,  InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      /** Arithmetic Instructions **/
      ADD    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.add,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ADDI   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.add,  Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ADDU   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.addu, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ADDIU  -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.addu, Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SUB    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.sub,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SUBU   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.subu, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SLT    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.slt,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SLTI   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.slt,  Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SLTU   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.sltu, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SLTIU  -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.sltu, Op1Recipe.rs,      Op2Recipe.imm,  BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      MUL    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.mul,   OpExtRecipe.zero),
      MULT   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.mult,   OpExtRecipe.zero),
      MULTU  -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.multu,   OpExtRecipe.zero),
      DIV    -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.div,   OpExtRecipe.zero),
      DIVU   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.divu,   OpExtRecipe.zero),
      MADD   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.madd,   OpExtRecipe.zero),
      MADDU  -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.maddu,   OpExtRecipe.zero),
      MSUB   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.msub,   OpExtRecipe.zero),
      MSUBU  -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.msubu,   OpExtRecipe.zero),
      CLO    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.clo,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      CLZ    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.clz,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      /** Branch and Jump Instructions **/
      BEQ    -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.beq,   RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BGEZ   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.bgez,  RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BGEZAL -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.bgezal,RegDst.GPR31, ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BGTZ   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.bgtz,  RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BLEZ   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.blez,  RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BLTZ   -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.bltz,  RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BLTZAL -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.bltzal,RegDst.GPR31, ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      BNE    -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.bne,   RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      J      -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.j,     RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      JAL    -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.jal,   RegDst.GPR31, ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      JALR   -> List(true.B,  LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.jalr,  RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      JR     -> List(false.B, LoadMode.disable,  StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.jr,    RegDst.rd,    ImmRecipe.sExt, InstrType.bru, MDUOp.none,   OpExtRecipe.zero),
      /** Load, Store, and Memory Control Instructions **/
      LB     -> List(true.B,  LoadMode.byteS,    StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      LBU    -> List(true.B,  LoadMode.byteU,    StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      LH     -> List(true.B,  LoadMode.halfS,    StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      LHU    -> List(true.B,  LoadMode.halfU,    StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      LW     -> List(true.B,  LoadMode.word,     StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      // LL     -> List(true.B,  LoadMode.word,     StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      LWL    -> List(true.B,  LoadMode.lwl,      StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.rd),
      LWR    -> List(true.B,  LoadMode.lwr,      StoreMode.disable,   ALUOp.none,  Op1Recipe.rs,  Op2Recipe.zero,BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.rd),
      SB     -> List(false.B, LoadMode.disable,  StoreMode.byte,      ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      SH     -> List(false.B, LoadMode.disable,  StoreMode.halfword,  ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      SW     -> List(false.B, LoadMode.disable,  StoreMode.word,      ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      // SC     -> List(true.B,  LoadMode.disable,  StoreMode.word,      ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rt,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      SWL    -> List(false.B, LoadMode.disable,  StoreMode.swl,       ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      SWR    -> List(false.B, LoadMode.disable,  StoreMode.swr,       ALUOp.none,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.none,   OpExtRecipe.zero),
      /** Move Instructions **/
      /* MFHI */
      /** Move Instructions **/
      MOVN   -> List(true.B, LoadMode.disable,  StoreMode.disable,    ALUOp.movn,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.rd),
      MOVZ   -> List(true.B, LoadMode.disable,  StoreMode.disable,    ALUOp.movz,  Op1Recipe.rs,  Op2Recipe.rt,  BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.rd),
      /* MFHI */
      MFHI   -> List(true.B, LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.zero, Op2Recipe.zero, BJCond.none, RegDst.rd, ImmRecipe.sExt, InstrType.mdu, MDUOp.mfhi,   OpExtRecipe.zero),
      /* MFLO */
      MFLO   -> List(true.B, LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.zero, Op2Recipe.zero, BJCond.none, RegDst.rd, ImmRecipe.sExt, InstrType.mdu, MDUOp.mflo,   OpExtRecipe.zero),
      /* MTHI */
      MTHI   -> List(false.B,LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.rs,   Op2Recipe.zero, BJCond.none, RegDst.rd, ImmRecipe.sExt, InstrType.mdu, MDUOp.mthi,   OpExtRecipe.zero),
      /* MTLO */
      MTLO   -> List(false.B,LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.rs,   Op2Recipe.zero, BJCond.none, RegDst.rd, ImmRecipe.sExt, InstrType.mdu, MDUOp.mtlo,   OpExtRecipe.zero),
      /* MTC0 */
      MTC0   -> List(false.B,LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.zero, Op2Recipe.rt,   BJCond.none, RegDst.rd, ImmRecipe.sExt, InstrType.mdu, MDUOp.mtc0,   OpExtRecipe.zero),
      /* MFC0 */
      MFC0   -> List(true.B, LoadMode.disable, StoreMode.disable, ALUOp.none, Op1Recipe.zero, Op2Recipe.zero, BJCond.none, RegDst.rt, ImmRecipe.sExt, InstrType.mdu, MDUOp.mfc0,   OpExtRecipe.zero),
      /** Shift Instructions **/
      SLL    -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.sll,  Op1Recipe.shamt,   Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SLLV   -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.sll,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SRA    -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.sra,  Op1Recipe.shamt,   Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SRAV   -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.sra,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SRL    -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.srl,  Op1Recipe.shamt,   Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SRLV   -> List(true.B,  LoadMode.disable, StoreMode.disable,    ALUOp.srl,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      /** Trap Instructions **/
      TEQ    -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.teq,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TEQI   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.teqi, Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TGE    -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tge,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TGEI   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tgei, Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TGEU   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tgeu, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TGEIU  -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tgeiu,Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TLT    -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tlt,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TLTI   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tlti, Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TLTU   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tltu, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TLTIU  -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tltiu,Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.uExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TNE    -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tne,  Op1Recipe.rs,      Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      TNEI   -> List(false.B,  LoadMode.disable, StoreMode.disable,   ALUOp.tnei, Op1Recipe.rs,      Op2Recipe.imm,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),

      /** Syscall, currently Halt **/
      SYSCALL -> List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      BREAK   -> List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      ERET    -> List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      SYNC    -> List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.sll,  Op1Recipe.zero,    Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),
      /** TLB instruction**/
      TLBP   ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.tlbp,   OpExtRecipe.zero),
      TLBR   ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.tlbr,   OpExtRecipe.zero),
      TLBWI  ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.tlbwi,   OpExtRecipe.zero),
      TLBWR  ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.zero,    Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.tlbwr,   OpExtRecipe.zero),

      /** Cache instruction */
      CACHEI ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.mdu, MDUOp.cache,   OpExtRecipe.zero),
      // temp
      CACHED ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.zero, BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.lsu, MDUOp.cache,    OpExtRecipe.zero),
      /** Likely instruction */
      // BEQL   ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.none, Op1Recipe.rs,      Op2Recipe.rt,   BJCond.beql,  RegDst.rd,   ImmRecipe.sExt, InstrType.alu, MDUOp.none,    OpExtRecipe.zero),
      /** PREF treated as nop  */
      PREF   ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.sll,  Op1Recipe.zero,    Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero),

      WAIT   ->  List(false.B, LoadMode.disable, StoreMode.disable,   ALUOp.sll,  Op1Recipe.zero,    Op2Recipe.rt,   BJCond.none, RegDst.rd,    ImmRecipe.sExt, InstrType.alu, MDUOp.none,   OpExtRecipe.zero)

    )
  )

  io.regWriteEn    := signals(0)
  io.loadMode      := signals(1)
  io.storeMode     := signals(2)
  io.aluOp         := signals(3)
  io.op1Recipe     := signals(4)
  io.op2Recipe     := signals(5)
  io.bjCond        := signals(6)
  io.regDst        := signals(7)
  io.immRecipe     := signals(8)
  io.instrType     := signals(9)
  io.mduOp         := signals(10)
  io.opExtRecipe   := signals(11)
}

object LoadMode {
  private val amount = 8
  val width = log2Up(amount)

  val disable = 0.U(width.W)
  val word    = 1.U(width.W)
  val byteS   = 2.U(width.W)
  val halfS   = 3.U(width.W)
  val byteU   = 4.U(width.W)
  val halfU   = 5.U(width.W)
  val lwl     = 6.U(width.W)
  val lwr     = 7.U(width.W)
  def en(lm: UInt) = {
    assert(lm.getWidth == width)

    lm =/= disable
  }
}

object StoreMode {
  // 共用
  private val amount = 6
  val width = log2Up(amount)

  val disable  = 0.U(width.W)
  val word     = 1.U(width.W)
  val byte     = 2.U(width.W)
  val halfword = 3.U(width.W)
  val swl      = 4.U(width.W)
  val swr      = 5.U(width.W)

  def en(sm: UInt) = {
    assert(sm.getWidth == width)

    sm =/= disable
  }
}

// ATTENTION: Ensure Op1Recipe.width >= Op2Recipe.width (had better ==)
object Op1Recipe {
  val width = 3

  val rs      = 0.U(width.W)
  val pcPlus8 = 1.U(width.W)
  val shamt   = 2.U(width.W)
  val zero    = 3.U(width.W)
  val hi      = 4.U(width.W)
  val lo      = 5.U(width.W)
}

object Op2Recipe {
  val width = 2

  val rt   = 0.U(width.W)
  val imm  = 1.U(width.W)
  val zero = 2.U(width.W)
}

object OpExtRecipe {
  val width = 1

  val rd   = 0.U(width.W)
  val zero = 1.U(width.W)
}

object BJCond {
  val amount = 14
  val width = log2Up(amount)

  val none    = 0.U(width.W)
  val beq     = 1.U(width.W)
  val bgez    = 2.U(width.W)
  val bgezal  = 3.U(width.W)
  val bgtz    = 4.U(width.W)
  val blez    = 5.U(width.W)
  val bltz    = 6.U(width.W)
  val bltzal  = 7.U(width.W)
  val bne     = 8.U(width.W)
  val j       = 9.U(width.W)
  val jal     = 10.U(width.W)
  val jalr    = 11.U(width.W)
  val jr      = 12.U(width.W)
  val beql    = 13.U(width.W)
}

object RegDst {
  val width = 2

  val rt    = 0.U(width.W)
  val rd    = 1.U(width.W)
  val GPR31 = 2.U(width.W)
}

object ImmRecipe {
  val width = 2

  val sExt = 0.U(width.W)
  val uExt = 1.U(width.W)
  val lui  = 2.U(width.W)
}

object InstrType {
  val width = 2

  val alu = 0.U(width.W)
  val mdu = 1.U(width.W)
  val lsu = 2.U(width.W)
  val bru = if (enableBru) 3.U(width.W) else 0.U(width.W)
}

object MDUOp {
  private val amount = 22
  val width = log2Up(amount)

  val none  = 0.U(width.W)
  val mul   = 1.U(width.W)
  val mult  = 2.U(width.W)
  val multu = 3.U(width.W)
  val div   = 4.U(width.W)
  val divu  = 5.U(width.W)
  val mfhi  = 6.U(width.W)
  val mflo  = 7.U(width.W)
  val mthi  = 8.U(width.W)
  val mtlo  = 9.U(width.W)
  val mfc0  = 10.U(width.W)
  val mtc0  = 11.U(width.W)
  val tlbp  = 12.U(width.W)
  val tlbr  = 13.U(width.W)
  val tlbwi = 14.U(width.W)
  val tlbwr = 15.U(width.W)
  val msub  = 16.U(width.W)
  val msubu = 17.U(width.W)
  val madd  = 18.U(width.W)
  val maddu = 19.U(width.W)
  val cache = 20.U(width.W)
  val ri    = 21.U(width.W)
}


object CacheOp {
  private val amount = 4
  val width = log2Up(amount)

  val inst = 0.U(width.W)
  val data = 1.U(width.W)
  val tert = 2.U(width.W)
  val seda = 3.U(width.W)
}