package mboom.core.backend.alu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

class AluIssueAwakeReadPort extends Bundle {
  val addr   = Input(UInt(phyRegAddrWidth.W))
  val awaken = Output(Bool())
}

class AluIssueAwake(detectWidth: Int = 8) extends Module {
  private val numOfAluPipeline = 2
  val io = IO(new Bundle {
    val awake = Input(Vec(numOfAluPipeline, Valid(UInt(phyRegAddrWidth.W))))
    val read  = Vec(2 * detectWidth, new AluIssueAwakeReadPort)

    val flush = Input(Bool())
  })

  val awaken     = RegInit(0.U.asTypeOf(Vec(phyRegAmount, Bool())))

  val nextAwaken = WireInit(0.U.asTypeOf(Vec(phyRegAmount, Bool())))
  for (i <- 0 until phyRegAmount) {
    nextAwaken(i) :=
      (io.awake(0).bits === i.U && io.awake(0).valid) || (io.awake(1).bits === i.U && io.awake(1).valid)
  }

  for (i <- 0 until 2 * detectWidth) {
    io.read(i).awaken := awaken(io.read(i).addr)
  }

  when (io.flush) {
    awaken := 0.U.asTypeOf(Vec(phyRegAmount, Bool()))
  } .otherwise {
    awaken := nextAwaken
  }
}
