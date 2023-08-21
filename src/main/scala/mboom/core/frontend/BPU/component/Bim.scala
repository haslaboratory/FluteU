package mboom.core.frontend.BPU.component

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.components.TypedDualPortAsyncRam

case class BimParam(n_ways: Int, n_entries: Int)

class BimRequest(param: BimParam) extends Bundle {
  val reqValid = Input(Bool())
  val reqPc    = Input(Vec(param.n_ways, UInt(addrWidth.W)))
  val taken    = Output(Vec(param.n_ways, Bool()))
}

class BimCommit(param: BimParam) extends Bundle {
  val comValid    = Input(Vec(param.n_ways, Bool()))
  val comIsJump   = Input(Vec(param.n_ways, Bool()))
  val comPc       = Input(Vec(param.n_ways, UInt(addrWidth.W)))
  val comTaken    = Input(Vec(param.n_ways, Bool()))
}

class Bim(param: BimParam) extends Module {
  val WaysLen = log2Ceil(param.n_ways)
  val io = IO(new Bundle {
    val request = new BimRequest(param)
    val commit  = new BimCommit(param)
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

  def hash(addr: UInt, nEntries: Int): UInt = {
    assert(addr.getWidth == 32)
    addr(nEntries + 1 + WaysLen, 2 + WaysLen)
  }

  val bim = for (_ <- 0 until param.n_ways) yield {
    Module(new TypedDualPortAsyncRam(1 << param.n_entries, UInt(2.W)))
  }

  for (i <- 0 until param.n_ways) {
    bim(i).io.wea   := 0.B
    bim(i).io.addra := hash(io.request.reqPc(i), param.n_entries)
    bim(i).io.dina  := 0.U
  }

  for (i <- 0 until param.n_ways) {
    io.request.taken(i) := bim(i).io.douta(1)
  }

  //// commit
  for (i <- 0 until param.n_ways) {
    bim(i).io.web   := io.commit.comValid(i) && io.commit.comIsJump(i)
    bim(i).io.addrb := hash(io.commit.comPc(i), param.n_entries)
    bim(i).io.dinb  := next2bit(bim(i).io.doutb, io.commit.comTaken(i))
  }
}
