package mboom.core.frontend.BPU.component

import chisel3._
import chisel3.util._
import mboom.components.TypedDualPortAsyncRam
import mboom.config.CPUConfig._

case class BHTAsyncParam(n_ways: Int, hash_len: Int, hist_len: Int)

class BHTAsyncRequest(param: BHTAsyncParam) extends Bundle {
  val reqValid     = Input(Bool())
  val reqPc        = Input(Vec(param.n_ways, UInt(addrWidth.W)))
  val reqHistHash  = Output(Vec(param.n_ways, UInt((param.hist_len+param.hash_len).W)))
}

class BHTAsyncCommit(param: BHTAsyncParam) extends Bundle {
  val comValid    = Input(Vec(param.n_ways, Bool()))
  val comIsBranch = Input(Vec(param.n_ways, Bool()))
  val comPc       = Input(Vec(param.n_ways, UInt(addrWidth.W)))
  val comTaken    = Input(Vec(param.n_ways, Bool()))
  val comHistHash = Output(Vec(param.n_ways, UInt((param.hist_len+param.hash_len).W)))
}

class BHTAsync(param: BHTAsyncParam) extends Module {
  val WaysLen = log2Ceil(param.n_ways)
  val io = IO(new Bundle{
    val request = new BHTAsyncRequest(param)
    val commit  = new BHTAsyncCommit(param)
  })

  def hash(addr: UInt, nEntries: Int): UInt = {
    assert(addr.getWidth == 32)
    addr(nEntries + 1 + WaysLen, 2 + WaysLen)
  }

  //// definitions
  val bht = for (_ <- 0 until param.n_ways) yield
    Module(new TypedDualPortAsyncRam(1 << param.hash_len, UInt(param.hist_len.W)))

  //// request
  val reqHash  = io.request.reqPc.map(pc => hash(pc, param.hash_len))
  val reqValid = WireInit(io.request.reqValid)

  for (i <- 0 until param.n_ways) {
    bht(i).io.wea   := 0.B
    bht(i).io.addra := reqHash(i)
    bht(i).io.dina  := 0.U
  }

  for (i <- 0 until param.n_ways) {
    io.request.reqHistHash(i) := Cat(reqHash(i), bht(i).io.douta)
  }

  //// commit
  val comHash = io.commit.comPc.map(pc => hash(pc, param.hash_len))
  for (i <- 0 until param.n_ways) {
    bht(i).io.web   := io.commit.comValid(i) && io.commit.comIsBranch(i)
    bht(i).io.addrb := comHash(i)
    bht(i).io.dinb  := Cat(bht(i).io.doutb(param.hist_len-2, 0), io.commit.comTaken(i))
  }

  for (i <- 0 until param.n_ways) {
    io.commit.comHistHash(i) := Cat(comHash(i), bht(i).io.doutb)
  }

}
