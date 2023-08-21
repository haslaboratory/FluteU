package mboom.core.frontend.BPU

import chisel3._
import chisel3.util._
import mboom.core.frontend.BPU.component._
import mboom.config.CPUConfig._

class NLPEntry extends Bundle {
  val tag      = UInt(25.W)
  val bimState = UInt(2.W)
  val target   = UInt(addrWidth.W)
  val valid    = Bool()
}


class NLPRequest extends Bundle {
  val pc    = UInt(32.W)
}

class NLPResponse(nWays: Int) extends Bundle {
  val taken = Vec(nWays, Bool())
  val bta   = Vec(nWays, UInt(addrWidth.W))
}

@deprecated
class NLP extends Module {
  val nWays    = 2
  val nEntries = 16
  val hashLen  = log2Ceil(nEntries)
  val io = IO(new Bundle {
    val request  = Input(new NLPRequest())
    val response = Output(new NLPResponse(nWays))
    val commit   = Input(Vec(nWays, Valid(new BPUCommitEntry)))
  })

  def getHash(pc: UInt): UInt = {
    assert(pc.getWidth == 32)
    pc(hashLen+2, 3)
  }

  def getTag(pc: UInt): UInt = {
    assert(pc.getWidth == 32)
    pc(31, hashLen+3)
  }

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

  val ram = RegInit(0.U.asTypeOf(Vec(nWays, Vec(nEntries, new NLPEntry))))

  // request
  val taken  = Wire(Vec(nWays, Bool()))
  val target = Wire(Vec(nWays, UInt(addrWidth.W)))

  val requestPc = io.request.pc

  for (i <- 0 until nWays) {
    val reqHash = getHash(io.request.pc)
    val hit     = ram(i)(reqHash).valid && ram(i)(reqHash).tag === getTag(requestPc)
    taken(i)  := hit && ram(i)(reqHash).bimState(1)
    target(i) := ram(i)(reqHash).target
  }

  io.response.taken := taken
  io.response.bta   := target

  // commit
  val exc = io.commit(0).bits.pc(2).asBool

  val comValid = Mux(
    exc,
    VecInit(io.commit.map(_.valid).reverse),
    VecInit(io.commit.map(_.valid)),
  )
  val comWire  = Mux(
    exc,
    VecInit(io.commit.map(_.bits).reverse),
    VecInit(io.commit.map(_.bits))
  )
  for (i <- 0 until nWays) {
    when (comValid(i) && comWire(i).isBranch) {
      val comHash = getHash(comWire(i).pc)
      val update = BranchType.fromLHP(comWire(i).br_type)
      val updBim = ram(i)(comHash).bimState

      val newBim = next2bit(updBim, comWire(i).taken)

      ram(i)(comHash).valid    := 1.B
      ram(i)(comHash).target   := comWire(i).bta
      ram(i)(comHash).tag      := getTag(comWire(i).pc)
      ram(i)(comHash).bimState := Mux(update, newBim, 3.U)
    }
  }
}
