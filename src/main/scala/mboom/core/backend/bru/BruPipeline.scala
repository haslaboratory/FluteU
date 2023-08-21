package mboom.core.backend.bru

import chisel3._
import chisel3.util._
import mboom.core.backend.decode._
import mboom.core.components._
import mboom.core.backend.alu.{AluExWbBundle, AluPipelineUtil, AluWB, BypassBundle, BypassPair}
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.utils.{BranchRestoreUtils, ExecuteUtil}
import mboom.cp0.ExceptionBundle

class BruPipeline(nBrCount: Int) extends Module {
  val io = IO(new Bundle {
    val uop     = Flipped(Valid(new MicroBRUOp))
    val prfRead = Flipped(new RegFileReadIO)
    val wb      = Output(new AluWB)

    val bypass  = Input(Vec(2, new BypassPair))
    val flush   = Input(new ExecFlush(nBranchCount))

    // branch
    val brNowMask  = Input(UInt((nBranchCount+1).W))

    val brMissPred = Output(Bool())
    val brBta      = Output(UInt(addrWidth.W))
    val brTag      = Output(UInt(log2Ceil(nBranchCount).W))
    val brMask     = Output(UInt((nBranchCount+1).W))
    val brOriginalMask = Output(UInt((nBranchCount+1).W))
    val brRob      = Output(UInt(robEntryNumWidth.W))
  })
  val readIn = io.uop

  io.prfRead.r1Addr := readIn.bits.baseOp.rsAddr
  io.prfRead.r2Addr := readIn.bits.baseOp.rtAddr

  val read2Ex = WireInit(readIn.bits)
  val (op1, op2) =
    BruPipelineUtil.getOp(
      readIn.bits,
      Seq(io.prfRead.r1Data, io.prfRead.r2Data),
      io.bypass
    )
  read2Ex.baseOp.op1.op := op1
  read2Ex.baseOp.op2.op := op2

  io.wb.busyTable.bits := read2Ex.baseOp.writeRegAddr
  io.wb.busyTable.valid := readIn.valid && read2Ex.baseOp.regWriteEn

  val stage2 = Module(new StageReg(Valid(new MicroBRUOp)))

  stage2.io.in.bits  := read2Ex
  stage2.io.in.valid := readIn.valid

  val exIn = stage2.io.data

  val flag = WireInit(0.U.asTypeOf(new Flag))
  val x = exIn.bits.baseOp.op1.op
  val y = exIn.bits.baseOp.op2.op

  flag.equal := x === y
  flag.lessS := x.asSInt < y.asSInt
  flag.lessU := x.asUInt < y.asUInt

  // branch
  val (taken, target) = BruPipelineUtil.branchRes(exIn.bits, flag)
  val trueTarget = Mux(taken, target, exIn.bits.baseOp.pc + 8.U)

  val missPred = exIn.valid && (exIn.bits.predictBT =/= trueTarget)

//  when (missPred) {
//    printf(p"missPred: ${Hexadecimal(exIn.bits.baseOp.pc)}, trurTarget: ${Hexadecimal(trueTarget)}\n")
//  }

  val exceptions = WireInit(0.U.asTypeOf(new ExceptionBundle))
  val ex2Wb = Wire(new AluExWbBundle)
  ex2Wb.valid       := exIn.valid
  ex2Wb.robAddr     := exIn.bits.baseOp.robAddr
  ex2Wb.regWEn      := exIn.bits.baseOp.regWriteEn
  ex2Wb.regWData    := exIn.bits.baseOp.pc + 8.U
  ex2Wb.regWAddr    := exIn.bits.baseOp.writeRegAddr
  ex2Wb.exception   := exceptions
  ex2Wb.badvaddr    := exIn.bits.baseOp.pc
  ex2Wb.computeBT   := trueTarget
  ex2Wb.branchTaken := taken
  ex2Wb.branchFail  := (exIn.bits.predictBT =/= trueTarget)
  // stage 3 Write Back
  val stage3     = Module(new StageReg(new AluExWbBundle))
  val brMissPred = RegInit(0.B)
  val brTag      = RegInit(0.U(log2Ceil(nBrCount).W))
  val brMask     = RegInit(0.U((nBrCount+1).W))
  val brOriginalMask = RegInit(0.U((nBrCount+1).W))
  val brBta      = RegInit(0.U(addrWidth.W))

  stage3.io.in := ex2Wb
  brMissPred   :=
    exIn.valid && missPred && exIn.bits.brAlloc &&
      !ExecuteUtil.needFlush(io.flush, exIn.bits.baseOp)
  brTag        := exIn.bits.brTag
  brMask       := BranchRestoreUtils.generateRestoreMask(exIn.bits.baseOp.brMask, io.brNowMask)
  brOriginalMask := exIn.bits.baseOp.brMask
  brBta        := trueTarget

  val wbIn = stage3.io.data

  val rob = AluPipelineUtil.robFromAluExWb(wbIn)
  io.wb.rob             := rob
  io.wb.prf.writeAddr   := wbIn.regWAddr
  io.wb.prf.writeData   := wbIn.regWData
  io.wb.prf.writeEnable := wbIn.regWEn && wbIn.valid

  io.brMissPred := 0.B// brMissPred
  io.brTag      := brTag
  io.brMask     := brMask
  io.brBta      := brBta
  io.brOriginalMask := brOriginalMask
  io.brRob      := stage3.io.data.robAddr

  stage2.io.flush := !readIn.valid || ExecuteUtil.needFlush(io.flush, readIn.bits.baseOp)
  stage3.io.flush := !stage2.io.data.valid || ExecuteUtil.needFlush(io.flush, exIn.bits.baseOp)
  stage2.io.valid := 1.B
  stage3.io.valid := 1.B

}

object BruPipelineUtil {
  def branchRes(uop: MicroBRUOp, aluFlag: Flag) = {
    val branchTaken = MuxLookup(
      key = uop.bjCond,
      default = 0.B,
      mapping = Seq(
        BJCond.none   -> 0.B,
        BJCond.beq    -> aluFlag.equal,
        BJCond.bgez   -> !aluFlag.lessS,
        BJCond.bgezal -> !aluFlag.lessS,
        BJCond.bgtz   -> !(aluFlag.lessS || aluFlag.equal),
        BJCond.blez   -> (aluFlag.lessS || aluFlag.equal),
        BJCond.bltz   -> aluFlag.lessS,
        BJCond.bltzal -> aluFlag.lessS,
        BJCond.bne    -> !aluFlag.equal,
        BJCond.j      -> 1.B,
        BJCond.jal    -> 1.B,
        BJCond.jalr   -> 1.B,
        BJCond.jr     -> 1.B,
        BJCond.beql   -> aluFlag.equal,
      )
    )
    val branchAddr = WireInit(0.U(addrWidth.W)) // 默认情况下返回0

    when(uop.bjCond === BJCond.jr || uop.bjCond === BJCond.jalr) {
      branchAddr := uop.baseOp.op1.op
    }.elsewhen(uop.bjCond === BJCond.j || uop.bjCond === BJCond.jal) {
      branchAddr := uop.immeJumpAddr
    }.otherwise {
      branchAddr := uop.baseOp.pc + 4.U + Cat(uop.immediate, 0.U(2.W))
    }

    (branchTaken, branchAddr)
  }

  def getOp(bruOp: MicroBRUOp, prf: Seq[UInt], bypass: Vec[BypassPair]) = {
    assert(bypass.length == 2 && prf.length == 2)
    val op1 = MuxCase(
      prf(0),
      Seq(
        bruOp.baseOp.op1.valid                                         -> bruOp.baseOp.op1.op,
        ((bruOp.baseOp.rsAddr === bypass(0).regAddr) && bypass(0).valid) -> bypass(0).data,
        ((bruOp.baseOp.rsAddr === bypass(1).regAddr) && bypass(1).valid) -> bypass(1).data
      )
    )
    val op2 = MuxCase(
      prf(1),
      Seq(
        bruOp.baseOp.op2.valid                      -> bruOp.baseOp.op2.op,
        ((bruOp.baseOp.rtAddr === bypass(0).regAddr) && bypass(0).valid) -> bypass(0).data,
        ((bruOp.baseOp.rtAddr === bypass(1).regAddr) && bypass(1).valid) -> bypass(1).data
      )
    )

    (op1, op2)
  }
}
