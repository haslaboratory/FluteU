package mboom.core.backend.dispatch

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.{phyRegAddrWidth, robEntryAmount}
import mboom.core.backend.commit.ROBWrite
import mboom.core.backend.commit.ROBEntry
import mboom.core.backend.decode._
import mboom.core.backend.rename._

class Dispatchv2(nWays: Int = 2, nQueue: Int = 4) extends Module {
  require(nWays == 2 && nQueue == 4)

  val io = IO(new Bundle {
    val in = Vec(nWays, Flipped(Decoupled(new RenameOp)))
    val flush = Input(Bool())

    val rob = Vec(nWays, Flipped(new ROBWrite(robEntryAmount)))
    // ready 标志当前拍Queue是否有空间
    // 0, 1 -> ALU Queue
    // 2 -> LSU Queue
    // 3 -> MDU Queue
    // 4 -> BRU Queue
    val out0 = DecoupledIO(new MicroALUOp())
    val out1 = DecoupledIO(new MicroALUOp())
    val out2 = DecoupledIO(new MicroLSUOp())
    val out3 = DecoupledIO(new MicroMDUOp())
    // val out4 = DecoupledIO(new MicroBRUOp())

  })

  val alu0Valid = WireInit(0.B)
  val alu1Valid = WireInit(0.B)
  val lsuValid  = WireInit(0.B)
  val mduValid  = WireInit(0.B)
  val bruValid  = WireInit(0.B)

  // with valid
  val isALU = Wire(Vec(nWays, Bool()))
  val isLSU = Wire(Vec(nWays, Bool()))
  val isMDU = Wire(Vec(nWays, Bool()))
  // val isBRU = Wire(Vec(nWays, Bool()))

  for (i <- 0 until nWays) {
    isALU(i) := io.in(i).bits.uop.instrType === InstrType.alu && io.in(i).valid
    isLSU(i) := io.in(i).bits.uop.instrType === InstrType.lsu && io.in(i).valid
    isMDU(i) := io.in(i).bits.uop.instrType === InstrType.mdu && io.in(i).valid
    // isBRU(i) := io.in(i).bits.uop.instrType === InstrType.bru && io.in(i).valid
  }

  val dispatchAlu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroALUOp)))
  val dispatchLsu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroLSUOp)))
  val dispatchMdu = WireInit(0.U.asTypeOf(Vec(nWays, new MicroMDUOp)))
  val dispatchBru = WireInit(0.U.asTypeOf(Vec(nWays, new MicroBRUOp)))
  val robEntry = WireInit(0.U.asTypeOf(Vec(nWays, new ROBEntry)))
  for (i <- 0 until nWays) {
    dispatchAlu(i) := DispatchUtil.uOp2AluOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    dispatchLsu(i) := DispatchUtil.uOp2LsuOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    dispatchMdu(i) := DispatchUtil.uOp2MduOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    dispatchBru(i) := DispatchUtil.uOp2BruOp(io.in(i).bits.uop, io.in(i).bits.rename, io.rob(i).robAddr)
    robEntry(i)    := DispatchUtil.uOp2ROBEntry(io.in(i).bits.uop, io.in(i).bits.rename)
  }


  // alu ops dispatch
  val dualALU = isALU(0) && isALU(1)
  val aluSel0 = PriorityEncoder(isALU)
  io.out0.bits := MuxLookup(
    aluSel0,
    dispatchAlu(0),
    (0 until nWays).map(i => (i.U -> dispatchAlu(i)))
  )
  alu0Valid := isALU(aluSel0)

  val aluSel1 = 1.U
  io.out1.bits := dispatchAlu(1)
  alu1Valid    := isALU(0) && isALU(1)


  // lsu
  val dualLSU  = isLSU(0) && isLSU(1)
  val selLSU   = Mux(isLSU(0), 0.U, 1.U)
  io.out2.bits := dispatchLsu(selLSU)
  lsuValid     := isLSU(selLSU)

  // mdu
  val dualMDU  = isMDU(0) && isMDU(1)
  val selMDU   = Mux(isMDU(0), 0.U, 1.U)
  io.out3.bits := dispatchMdu(selMDU)
  mduValid     := isMDU(selMDU)

  // bru
//  val selBRU   = Mux(isBRU(0), 0.U, 1.U)
//  io.out4.bits := dispatchBru(selBRU)
//  bruValid     := isBRU(selBRU)

  io.rob(0).bits := robEntry(0)
  io.rob(1).bits := robEntry(1)

  // signal
  val issueCongest = Wire(Vec(nWays, Bool()))

  issueCongest(0) :=
    MuxCase(0.B, Seq(
      isALU(0) -> (!io.out0.ready),
      isLSU(0) -> (!io.out2.ready),
      isMDU(0) -> (!io.out3.ready),
      // isBRU(0) -> (!io.out4.ready)
    ))

  issueCongest(1) := issueCongest(0) ||
    MuxCase(0.B, Seq(
      dualALU  -> (!io.out1.ready),
      dualLSU  -> 1.B,
      dualMDU  -> 1.B,
      isALU(1) -> (!io.out0.ready),
      isLSU(1) -> (!io.out2.ready),
      isMDU(1) -> (!io.out3.ready),
      // isBRU(1) -> (!io.out4.ready)
    ))

  val robBusy = Wire(Vec(nWays, Bool()))

  robBusy(0) := io.in(0).valid && !io.rob(0).ready
  robBusy(1) := io.in(1).valid && !io.rob(1).ready

  io.out0.valid := alu0Valid && !issueCongest(aluSel0) && !robBusy(aluSel0)
  io.out1.valid := alu1Valid && !issueCongest(aluSel1) && !robBusy(aluSel1)
  io.out2.valid := lsuValid  && !issueCongest(selLSU)  && !robBusy(selLSU)
  io.out3.valid := mduValid  && !issueCongest(selMDU)  && !robBusy(selMDU)
  // io.out4.valid := bruValid  && !issueCongest(selBRU)  && !robBusy(selBRU)

  io.in(0).ready := !issueCongest(0) && !robBusy(0)
  io.in(1).ready := !issueCongest(1) && !robBusy(1)

  io.rob(0).valid := io.in(0).valid && !issueCongest(0)
  io.rob(1).valid := io.in(1).valid && !issueCongest(1)


}

