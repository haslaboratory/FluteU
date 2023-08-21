package mboom.core.backend.alu

import chisel3._
import chisel3.util._
import firrtl.PrimOps.Squeeze
import mboom.core.backend.decode.MicroOp
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.commit._
import mboom.core.components._
import mboom.cp0._
import mboom.core.components.ALU
import mboom.core.backend.decode._
import mboom.core.backend.utils.ExecuteUtil
import mboom.core.frontend.BPU.BranchType

class AluWB extends Bundle {
  val rob = new ROBCompleteBundle(robEntryNumWidth)
  val prf = new RegFileWriteIO

  val busyTable = Valid(UInt(phyRegAddrWidth.W)) // 实现了64个物理寄存器
}

class BypassPair extends Bundle {
  val valid   = Bool()
  val regAddr = UInt(phyRegAddrWidth.W)
  val data    = UInt(dataWidth.W)
}

class BypassBundle extends Bundle {
  val dataIn  = Input(Vec(2, new BypassPair))
  val dataOut = Output(new BypassPair)

  val regIn  = Input(Vec(2, Valid(UInt(phyRegAddrWidth.W))))
  val regOut = Output(Valid(UInt(phyRegAddrWidth.W)))
}

class AluPipeline(aluIndex: Int = 0) extends Module {
  val io = IO(new Bundle {
    // 无阻塞
    val uop     = Input(Valid(new AluEntry))
    val prfRead = Flipped(new RegFileReadIO)
    val prfReadExt = Flipped(new RegFileExtReadIO)
    val wb      = Output(new AluWB)

    val bypass = new BypassBundle
    val flush   = Input(new ExecFlush(nBranchCount))
  })

  /// stage 1: PRF Read & Bypass & BusyTable Checkout ---------------------------///
  val readIn = io.uop
  // 操作数3个来源： 立即数,Bypass,PRF
  io.prfRead.r1Addr := readIn.bits.uop.baseOp.rsAddr
  io.prfRead.r2Addr := readIn.bits.uop.baseOp.rtAddr
  io.prfReadExt.extAddr := readIn.bits.uop.baseOp.extAddr

  // bypass
  val (op1, op2, opExt) =
    AluPipelineUtil.getOp(
      readIn.bits.uop,
      Seq(io.prfRead.r1Data, io.prfRead.r2Data, io.prfReadExt.extData),
      io.bypass.dataIn,
      aluIndex
    )

  val read2Ex = WireInit(readIn.bits.uop)
  read2Ex.baseOp.op1.op     := op1
  read2Ex.baseOp.op2.op     := op2
  read2Ex.baseOp.opExt.op   := opExt
  read2Ex.baseOp.regWriteEn :=  readIn.bits.uop.baseOp.regWriteEn
  // busyTable check out
  io.wb.busyTable.bits  := read2Ex.baseOp.writeRegAddr
  io.wb.busyTable.valid := readIn.valid && read2Ex.baseOp.regWriteEn

  /// stage 2: ALU Execute & Branch Compute & Bypass Out ///
  val stage2      = Module(new StageReg(Valid(new MicroALUOp)))
  val bypassValid = RegInit(0.U.asTypeOf(Vec(2, Vec(2, Bool()))))

  stage2.io.in.bits  := read2Ex
  stage2.io.in.valid := readIn.valid

  val bypassMatchRs = WireInit(0.U.asTypeOf(Vec(2, Bool())))
  val bypassMatchRt = WireInit(0.U.asTypeOf(Vec(2, Bool())))
  for (i <- 0 until 2) {
    bypassMatchRs(i) :=
      !io.uop.bits.uop.baseOp.op1.valid &&
        io.bypass.regIn(i).valid &&
        (io.bypass.regIn(i).bits === readIn.bits.uop.baseOp.rsAddr)
    bypassMatchRt(i) :=
      !io.uop.bits.uop.baseOp.op2.valid &&
        io.bypass.regIn(i).valid &&
        (io.bypass.regIn(i).bits === readIn.bits.uop.baseOp.rtAddr)
  }

  bypassValid(0) := bypassMatchRs
  bypassValid(1) := bypassMatchRt

  val exIn = stage2.io.data

  // bypass reg
  io.bypass.regOut.valid := exIn.valid && exIn.bits.baseOp.regWriteEn
  io.bypass.regOut.bits  := exIn.bits.baseOp.writeRegAddr

  val exOp1 = MuxCase(exIn.bits.baseOp.op1.op, Seq(
    bypassValid(0)(0)  -> io.bypass.dataIn(0).data,
    bypassValid(0)(1)  -> io.bypass.dataIn(1).data
  ))

  val exOp2 = MuxCase(exIn.bits.baseOp.op2.op, Seq(
    bypassValid(1)(0) -> io.bypass.dataIn(0).data,
    bypassValid(1)(1) -> io.bypass.dataIn(1).data
  ))

  val exOpExt = exIn.bits.baseOp.opExt.op

  val alu = Module(new ALU)
  alu.io.aluOp := exIn.bits.aluOp
  alu.io.x     := exOp1
  alu.io.y     := exOp2
  alu.io.ext   := exOpExt

  val (taken, target) = AluPipelineUtil.branchRes(exIn.bits, alu.io.flag, exOp1)
  val trueTarget = Mux(taken, target, exIn.bits.baseOp.pc + 8.U)

  val s2WriteData = Mux(exIn.bits.bjCond === 0.U, alu.io.result, exIn.bits.baseOp.pc + 8.U)

  val missPred = exIn.valid && (exIn.bits.predictBT =/= trueTarget)

  val exceptions = WireInit(0.U.asTypeOf(new ExceptionBundle))
  exceptions.tlblInv  := exIn.bits.tlblInv
  exceptions.tlblRfl  := exIn.bits.tlblRfl
  exceptions.bp    := exIn.bits.break
  exceptions.ov    := alu.io.flag.ov
  exceptions.trap  := alu.io.flag.trap
  exceptions.ri    := exIn.bits.reservedI
  exceptions.sys   := exIn.bits.syscall
  exceptions.adELi := exIn.bits.baseOp.pc(1, 0) =/= 0.U

  val ex2Wb = Wire(new AluExWbBundle)
  ex2Wb.valid       := exIn.valid
  ex2Wb.robAddr     := exIn.bits.baseOp.robAddr
  ex2Wb.regWEn      := exIn.bits.baseOp.regWriteEn
  ex2Wb.regWData    := s2WriteData
  ex2Wb.regWAddr    := exIn.bits.baseOp.writeRegAddr
  ex2Wb.exception   := exceptions
  ex2Wb.badvaddr    := exIn.bits.baseOp.pc
  ex2Wb.computeBT   := trueTarget
  ex2Wb.branchTaken := taken
  // important: 非 branch 也会出现分支预测失败，需要 branch restore
  // beql 恒定按照branch Fail处理避免冲突
  ex2Wb.branchFail  :=  (exIn.bits.predictBT =/= trueTarget)

  /// stage 3: WriteBack --------------------------------///
  val stage3 = Module(new StageReg(new AluExWbBundle))
  stage3.io.in := ex2Wb

  val wbIn = stage3.io.data

  // bypass data
  io.bypass.dataOut.valid   := wbIn.valid && wbIn.regWEn
  io.bypass.dataOut.regAddr := wbIn.regWAddr
  io.bypass.dataOut.data    := wbIn.regWData

  val rob = AluPipelineUtil.robFromAluExWb(wbIn)
  io.wb.rob             := rob
  io.wb.prf.writeAddr   := wbIn.regWAddr
  io.wb.prf.writeData   := wbIn.regWData
  io.wb.prf.writeEnable := wbIn.regWEn && wbIn.valid

  stage2.io.flush := !readIn.valid         || ExecuteUtil.needFlush(io.flush, readIn.bits.uop.baseOp)
  stage3.io.flush := !stage2.io.data.valid || ExecuteUtil.needFlush(io.flush, exIn.bits.baseOp)
  stage2.io.valid := 1.B
  stage3.io.valid := 1.B

}

class AluExWbBundle extends Bundle {
  // 需要包含： ALU res, Branch res, Exception res
  val valid     = Bool() // 总体valid: 是否为气泡
  val robAddr   = UInt(robEntryNumWidth.W)
  val exception = new ExceptionBundle
  val badvaddr  = UInt(addrWidth.W)
  val regWEn    = Bool()
  val regWData  = UInt(dataWidth.W)
  val regWAddr  = UInt(phyRegAddrWidth.W)

  val computeBT   = UInt(addrWidth.W)
  val branchTaken = Bool()
  val branchFail  = Bool()
}

object BranchRestoreUtil {

}

object AluPipelineUtil {
  def getOp(aluOp: MicroALUOp, prf: Seq[UInt], bypass: Vec[BypassPair], aluIndex: Int) = {
    assert(bypass.length == 2 && prf.length == 3)
    val op1 = MuxCase(
      prf(0),
      Seq(
        aluOp.baseOp.op1.valid -> aluOp.baseOp.op1.op,
        ((aluOp.baseOp.rsAddr === bypass(0).regAddr) && bypass(0).valid) -> bypass(0).data,
        ((aluOp.baseOp.rsAddr === bypass(1).regAddr) && bypass(1).valid) -> bypass(1).data
      )
    )
    val op2 = MuxCase(
      prf(1),
      Seq(
        aluOp.baseOp.op2.valid -> aluOp.baseOp.op2.op,
        ((aluOp.baseOp.rtAddr === bypass(0).regAddr) && bypass(0).valid) -> bypass(0).data,
        ((aluOp.baseOp.rtAddr === bypass(1).regAddr) && bypass(1).valid) -> bypass(1).data
      )
    )
    val opExt = Wire(UInt(32.W))
    if (aluIndex == 0) {
      opExt :=
        MuxCase(
        prf(2),
        Seq(
          aluOp.baseOp.opExt.valid -> aluOp.baseOp.opExt.op,
          ((aluOp.baseOp.extAddr === bypass(0).regAddr) && bypass(0).valid) -> bypass(0).data,
          ((aluOp.baseOp.extAddr === bypass(1).regAddr) && bypass(1).valid) -> bypass(1).data
        ))
    } else {
      opExt := 0.U
    }
    (op1, op2, opExt)
  }

  def robFromAluExWb(wbIn: AluExWbBundle): ROBCompleteBundle = {
    val rob = Wire(new ROBCompleteBundle(robEntryNumWidth))
    rob.exception := wbIn.exception
    rob.robAddr   := wbIn.robAddr
    // rob.regWEn    := ex2Wb.regWEn
    rob.valid    := wbIn.valid

    rob.branchTaken := wbIn.branchTaken
    rob.computeBT   := wbIn.computeBT
    rob.branchFail  := wbIn.branchFail
    rob.badvaddr    := wbIn.badvaddr
    rob.hiRegWrite  := 0.U.asTypeOf(Valid(UInt(32.W)))
    rob.loRegWrite  := 0.U.asTypeOf(Valid(UInt(32.W)))

    rob.unCache     := 0.B

    rob
  }

  def branchRes(uop: MicroALUOp, aluFlag: Flag, op1: UInt) = {
    val branchTaken = MuxLookup(
      key = uop.bjCond,
      default = 0.B,
      mapping = Seq(
        BJCond.none -> 0.B,
        BJCond.beq -> aluFlag.equal,
        BJCond.bgez -> !aluFlag.lessS,
        BJCond.bgezal -> !aluFlag.lessS,
        BJCond.bgtz -> !(aluFlag.lessS || aluFlag.equal),
        BJCond.blez -> (aluFlag.lessS || aluFlag.equal),
        BJCond.bltz -> aluFlag.lessS,
        BJCond.bltzal -> aluFlag.lessS,
        BJCond.bne -> !aluFlag.equal,
        BJCond.j -> 1.B,
        BJCond.jal -> 1.B,
        BJCond.jalr -> 1.B,
        BJCond.jr -> 1.B,
        BJCond.beql -> aluFlag.equal,
      )
    )
    val branchAddr = WireInit(0.U(addrWidth.W)) // 默认情况下返回0

    when(uop.bjCond === BJCond.jr || uop.bjCond === BJCond.jalr) {
      branchAddr := op1
    }.elsewhen(uop.bjCond === BJCond.j || uop.bjCond === BJCond.jal) {
      branchAddr := uop.immeJumpAddr
    }.otherwise {
      branchAddr := uop.baseOp.pc + 4.U + Cat(uop.immediate, 0.U(2.W))
    }

    (branchTaken, branchAddr)
  }

}
