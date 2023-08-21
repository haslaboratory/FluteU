package mboom.core.backend.dispatch

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{phyRegAddrWidth, robEntryAmount}
import mboom.core.backend.commit.ROBWrite
import mboom.core.backend.commit.ROBEntry
import mboom.core.backend.decode._
import mboom.core.backend.rename._

class Dispatch(nWays: Int = 2, nQueue: Int = 4) extends Module {
  require(nWays == 2 && nQueue == 4)

  val io = IO(new Bundle {
    val in = Vec(nWays, Flipped(Decoupled(new RenameOp)))
    val flush = Input(Bool())

    val rob = Vec(nWays, Flipped(new ROBWrite(robEntryAmount)))
    // ready 标志当前拍Queue是否有空间
    // 0, 1 -> ALU Queue
    // 2 -> LSU Queue
    // 3 -> MDU Queue
    val out0 = DecoupledIO(new MicroALUOp())
    val out1 = DecoupledIO(new MicroALUOp())
    val out2 = DecoupledIO(new MicroLSUOp())
    val out3 = DecoupledIO(new MicroMDUOp())

  })

  val alu0Valid = WireInit(0.B)
  val alu1Valid = WireInit(0.B)
  val lsuValid  = WireInit(0.B)
  val mduValid  = WireInit(0.B)

  // with valid
  val isALU = Wire(Vec(nWays, Bool()))
  val isLSU = Wire(Vec(nWays, Bool()))
  val isMDU = Wire(Vec(nWays, Bool()))

  for (i <- 0 until nWays) {
    isALU(i) := io.in(i).bits.uop.instrType === InstrType.alu && io.in(i).valid
    isLSU(i) := io.in(i).bits.uop.instrType === InstrType.lsu && io.in(i).valid
    isMDU(i) := io.in(i).bits.uop.instrType === InstrType.mdu && io.in(i).valid
  }

  val dispatchAlu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroALUOp)))
  val dispatchLsu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroLSUOp)))
  val dispatchMdu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroMDUOp)))
  val robEntry = WireInit(0.U.asTypeOf(Vec(nWays, new ROBEntry)))
  for (i <- 0 until nWays) {
    dispatchAlu(i) := DispatchUtil.uOp2AluOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    dispatchLsu(i) := DispatchUtil.uOp2LsuOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    dispatchMdu(i) := DispatchUtil.uOp2MduOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    robEntry(i)    := DispatchUtil.uOp2ROBEntry(io.in(i).bits.uop, io.in(i).bits.rename)
  }

  // process dual MDU / LSU
  val delayDispatchLsu = RegInit(0.U.asTypeOf(new MicroLSUOp))
  val delayDispatchMdu = RegInit(0.U.asTypeOf(new MicroMDUOp))
  when(isLSU(0) && isLSU(1) && io.out2.fire) {
    delayDispatchLsu := dispatchLsu(1)
  }
  when(isMDU(0) && isMDU(1) && io.out3.fire) {
    delayDispatchMdu := dispatchMdu(1)
  }

  // alu ops dispatch
  val aluSel0 = PriorityEncoder(isALU)
  io.out0.bits := MuxLookup(
    aluSel0,
    dispatchAlu(0),
    (0 until nWays).map(i => (i.U -> dispatchAlu(i)))
  )
  alu0Valid := isALU(aluSel0)

  val aluSel1 = PriorityEncoder(isALU.asUInt & ~UIntToOH(aluSel0))
  io.out1.bits := MuxLookup(
    aluSel1,
    dispatchAlu(0),
    (0 until nWays).map(i => (i.U -> dispatchAlu(i)))
  )
  alu1Valid := isALU(aluSel1) && aluSel1 =/= aluSel0

  // FSM 状态机
  val idle :: dualLSU :: dualMDU :: Nil = Enum(3)
  val state                             = RegInit(idle)

  // lsu
  val selLSU = Mux(isLSU(0) && state =/= dualLSU, 0.U, 1.U)
  io.out2.bits := MuxCase(dispatchLsu(1), Seq(
    (state === dualLSU) -> delayDispatchLsu,
    (state =/= dualLSU && isLSU(0)) -> dispatchLsu(0)
  ))
  lsuValid       := isLSU(selLSU)

  // mdu
  val selMDU = Mux(isMDU(0) && state =/= dualMDU, 0.U, 1.U)
  io.out3.bits := MuxCase(dispatchMdu(1), Seq(
    (state === dualMDU) -> delayDispatchMdu,
    (state =/= dualMDU && isMDU(0)) -> dispatchMdu(0)
  ))
  mduValid       := isMDU(selMDU)

  switch(state) {
    is(idle) {
      when(!io.flush) {
        when(isMDU(0) && isMDU(1) && io.out3.fire) {
          state := dualMDU
        }.elsewhen(isLSU(0) && isLSU(1) && io.out2.fire) {
          state := dualLSU
        }
      }
    }

    is(dualLSU) {
      when(io.flush) {
        state := idle
      }.otherwise {
        state := Mux(io.out2.fire, idle, dualLSU)
      }
    }

    is(dualMDU) {
      when(io.flush) {
        state := idle
      }.otherwise {
        state := Mux(io.out3.fire, idle, dualMDU)
      }
    }
  }

  io.rob(0).bits := robEntry(0)
  io.rob(1).bits := robEntry(1)

  val robBusy =
    (io.in(0).valid && !io.rob(0).ready) ||
    (io.in(1).valid && !io.rob(1).ready)
  // note: 对于 0->非alu, 1->alu 指令的情况，将alu指令发向0
  val issueCongested =
    (alu0Valid && !io.out0.ready) ||
    (alu1Valid && !io.out1.ready) ||
    (lsuValid  && !io.out2.ready) ||
    (mduValid  && !io.out3.ready)

  val cannotDispatch = issueCongested || robBusy

  val stallReq =
    cannotDispatch ||
    (state === idle && isMDU(0) && isMDU(1)) ||
    (state === idle && isLSU(0) && isLSU(1))

  for (i <- 0 until nWays) {
    io.in(i).ready := !stallReq
  }

  io.out0.valid := alu0Valid && !cannotDispatch
  io.out1.valid := alu1Valid && !cannotDispatch
  io.out2.valid := lsuValid  && !cannotDispatch
  io.out3.valid := mduValid  && !cannotDispatch

  val modified0 = io.in(0).valid && !stallReq
  val modified1 = io.in(1).valid && !stallReq

  io.rob(0).valid := modified0
  io.rob(1).valid := modified1


}


object DispatchUtil {
  def uOp2ROBEntry(uop: MicroOp, re: RenameEntry): ROBEntry = {
    val robEntry = Wire(new ROBEntry)
    robEntry.pc        := uop.pc
    robEntry.complete  := 0.B
    robEntry.logicReg  := uop.writeRegAddr
    robEntry.physicReg := re.writeReg
    robEntry.originReg := re.originReg
    robEntry.exception := DontCare
    robEntry.instrType := uop.instrType
    robEntry.regWEn    := uop.regWriteEn
    robEntry.memWMode  := uop.storeMode
    robEntry.computeBT   := DontCare
    robEntry.branchFail  := DontCare
    robEntry.inSlot      := uop.inSlot
    robEntry.branchTaken := DontCare
    robEntry.brType      := uop.brType
    robEntry.brCheck     := uop.brCheck
    robEntry.brMask      := re.brMask
    robEntry.brAlloc     := re.brAlloc

    robEntry.hiRegWrite  := DontCare
    robEntry.loRegWrite  := DontCare
    robEntry.badvaddr    := DontCare
    robEntry.eret        := uop.eret

    robEntry.instruction := uop.instruction

    robEntry.unCache     := DontCare

    robEntry
  }

  def uOpRename(uop: MicroOp, re: RenameEntry, killed: Bool): MicroOp = {
    val dispatchUop = Wire(new MicroOp(rename = true))
    dispatchUop              := uop
    dispatchUop.rsAddr       := re.srcL
    dispatchUop.rtAddr       := re.srcR
    dispatchUop.writeRegAddr := re.writeReg

    dispatchUop
  }

  def uOp2BaseOp(uop: MicroOp, re: RenameEntry, robAddr: UInt): MicroBaseOp = {
    val baseOp = WireInit(0.U.asTypeOf(new MicroBaseOp))
    baseOp.regWriteEn   := uop.regWriteEn
    baseOp.op1          := uop.op1
    baseOp.op2          := uop.op2
    baseOp.opExt        := uop.opExt

    baseOp.writeRegAddr := re.writeReg
    baseOp.rsAddr       := re.srcL
    baseOp.rtAddr       := re.srcR
    baseOp.extAddr      := re.originReg

    baseOp.pc           := uop.pc
    baseOp.robAddr      := robAddr

    baseOp.brMask       := re.brMask

    baseOp
  }
  def uOp2AluOp(uop: MicroOp, re: RenameEntry, robAddr: UInt): MicroALUOp = {
    val aluOp = WireInit(0.U.asTypeOf(new MicroALUOp))
    aluOp.baseOp       := uOp2BaseOp(uop, re, robAddr)

    aluOp.aluOp        := uop.aluOp
    aluOp.bjCond       := uop.bjCond
    aluOp.immediate    := uop.immediate
    aluOp.immeJumpAddr := uop.immeJumpAddr
    aluOp.predictBT    := uop.predictBT

    aluOp.tlblInv      := uop.tlblInv
    aluOp.tlblRfl      := uop.tlblRfl
    aluOp.syscall      := uop.syscall
    aluOp.break        := uop.break
    aluOp.eret         := uop.eret
    aluOp.reservedI    := uop.reservedI

    aluOp
  }

  def uOp2LsuOp(uop: MicroOp, re: RenameEntry, robAddr: UInt): MicroLSUOp = {
    val lsuOp = WireInit(0.U.asTypeOf(new MicroLSUOp))
    lsuOp.baseOp    := uOp2BaseOp(uop, re, robAddr)

    lsuOp.loadMode   := uop.loadMode
    lsuOp.storeMode  := uop.storeMode
    lsuOp.vAddr      := uop.immediate

    lsuOp
  }

  def uOp2MduOp(uop: MicroOp, re: RenameEntry, robAddr: UInt): MicroMDUOp = {
    val mduOp = WireInit(0.U.asTypeOf(new MicroMDUOp))
    mduOp.baseOp     := uOp2BaseOp(uop, re, robAddr)

    mduOp.mduOp      := uop.mduOp
    mduOp.cacheOp    := uop.cacheOp
    mduOp.immediate  := uop.immediate
    mduOp.cp0RegAddr := uop.cp0RegAddr
    mduOp.cp0RegSel  := uop.cp0RegSel

    mduOp
  }

  def uOp2BruOp(uop: MicroOp, re: RenameEntry, robAddr: UInt): MicroBRUOp = {
    val bruOp = WireInit(0.U.asTypeOf(new MicroBRUOp))
    bruOp.baseOp     := uOp2BaseOp(uop, re, robAddr)

    bruOp.bjCond       := uop.bjCond
    bruOp.immediate    := uop.immediate
    bruOp.immeJumpAddr := uop.immeJumpAddr
    bruOp.predictBT    := uop.predictBT
    bruOp.brAlloc      := re.brAlloc
    bruOp.brTag        := re.brTag

    bruOp
  }
}