package mboom.core.components

import chisel3._
import chisel3.util._
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroUnitOp
import mboom.config.CPUConfig._
import mboom.core.backend.utils.ExecuteUtil

class StageRegv2IO[+T <: MicroUnitOp](gen: T) extends Bundle {
  val in    = Flipped(Decoupled(gen))

  val out   = Decoupled(gen)

  val flush = Input(new ExecFlush(nBranchCount))
}

// 阻塞 flush
class StageRegv2[+T <: MicroUnitOp](val gen: T) extends Module {
  val io = IO(new StageRegv2IO[T](gen))

  val reg = RegInit(0.U.asTypeOf(Valid(gen)))

  val flushIn  = ExecuteUtil.needFlush(io.flush, io.in.bits.baseOp)
  val flushNow = ExecuteUtil.needFlush(io.flush, reg.bits.baseOp)

  when (!reg.valid || io.out.ready) {
    reg.valid := io.in.valid && !flushIn
    reg.bits  := io.in.bits
  } .otherwise {
    reg.valid := !flushNow
    reg.bits  := reg.bits
  }

  io.in.ready := !reg.valid || io.out.ready

  io.out.valid := reg.valid
  io.out.bits  := reg.bits
}
