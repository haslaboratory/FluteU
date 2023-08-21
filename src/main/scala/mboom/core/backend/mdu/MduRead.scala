package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.{MicroMDUOp, MicroOp}
import mboom.core.components.{MuxStageReg, MuxStageRegMode, RegFileReadIO, StageRegv2}
import mboom.cp0.CP0Read
import mboom.config.CPUConfig._

class MduRead extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MicroMDUOp))
    val out = Decoupled(new MicroMDUOp)

    val prf = Flipped(new RegFileReadIO)

    val flush = Input(new ExecFlush(nBranchCount))
  })

  val uop = WireInit(io.in.bits)

  io.prf.r1Addr := io.in.bits.baseOp.rsAddr
  io.prf.r2Addr := io.in.bits.baseOp.rtAddr

  val uopWithData = WireInit(uop)
  uopWithData.baseOp.op1.op := Mux(uop.baseOp.op1.valid, uop.baseOp.op1.op, io.prf.r1Data)
  uopWithData.baseOp.op2.op := Mux(uop.baseOp.op2.valid, uop.baseOp.op2.op, io.prf.r2Data)

  val stage = Module(new StageRegv2(new MicroMDUOp))
  stage.io.flush := io.flush

  // datapath
  stage.io.in.bits  := uopWithData
  stage.io.in.valid := io.in.valid
  io.in.ready       := stage.io.in.ready

  io.out <> stage.io.out
}
