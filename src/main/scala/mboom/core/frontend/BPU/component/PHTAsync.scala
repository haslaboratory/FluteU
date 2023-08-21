package mboom.core.frontend.BPU.component

import chisel3._
import chisel3.util._
import mboom.components.TypedDualPortAsyncRam
import mboom.config.CPUConfig._

case class PHTAsyncParam(n_ways: Int, hash_len: Int, hist_len: Int)

class PHTAsyncRequest(param: PHTAsyncParam) extends Bundle {
  val reqValid = Input(Bool())
  val reqHash  = Input(Vec(param.n_ways, UInt((param.hash_len+param.hist_len).W)))
  val taken    = Output(Vec(param.n_ways, Bool()))
}

class PHTAsyncCommit(param: PHTAsyncParam) extends Bundle {
  val comValid    = Input(Vec(param.n_ways, Bool()))
  val comIsBranch = Input(Vec(param.n_ways, Bool()))
  val comHistHash = Input(Vec(param.n_ways, UInt((param.hist_len+param.hash_len).W)))
  val comTaken    = Input(Vec(param.n_ways, Bool()))
}
class PHTAsync(param: PHTAsyncParam) extends Module {
  val io = IO(new Bundle {
    val request = new PHTAsyncRequest(param)
    val commit  = new PHTAsyncCommit(param)
  })

  def next2bit(old2bit: UInt, taken: Bool): UInt = {
    val new2bit = WireInit(0.U(2.W))
    when(old2bit === "b10".U && taken) {
      new2bit := old2bit
    }.elsewhen(old2bit === "b01".U && !taken) {
      new2bit := old2bit
    }.elsewhen(taken) {
      new2bit := old2bit - 1.U
    }.otherwise {
      new2bit := old2bit + 1.U
    }
    new2bit
  }

  val pht = for (_ <- 0 until param.n_ways) yield
    Module(new TypedDualPortAsyncRam(1 << (param.hash_len+param.hist_len), UInt(2.W)))

  //// request
  val reqValid = WireInit(io.request.reqValid)
  val reqHash  = WireInit(io.request.reqHash)

  for (i <- 0 until param.n_ways) {
    pht(i).io.wea   := 0.B
    pht(i).io.addra := reqHash(i)
    pht(i).io.dina  := 0.U
  }

  for (i <- 0 until param.n_ways) {
    io.request.taken(i) := reqValid && pht(i).io.douta(1)
  }
  //// commit
  for (i <- 0 until param.n_ways) {
    pht(i).io.web   := io.commit.comValid(i) && io.commit.comIsBranch(i)
    pht(i).io.addrb := io.commit.comHistHash(i)
    pht(i).io.dinb  := next2bit(pht(i).io.doutb, io.commit.comTaken(i))
  }

}
