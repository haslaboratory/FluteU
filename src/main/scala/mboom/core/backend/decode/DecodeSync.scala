package mboom.core.backend.decode

import chisel3._
import chisel3.util._

class DecodeSync(val nWays: Int) extends Module {
  assert(nWays == 2)
  val io = IO(new Bundle {
    val in    = Vec(nWays, Flipped(Valid(Bool())))
    val out   = Output(Bool())

    val flush = Input(Bool())
  })

  val delaySync = RegInit(0.B)

  io.out := delaySync

  when (io.flush) {
    delaySync := 0.B
  } .elsewhen(io.in(1).valid) {
    delaySync := io.in(1).bits
  } .elsewhen(io.in(0).valid) {
    delaySync := io.in(0).bits
  }
}
