package mboom.core.frontend.BPU.component

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.frontend.BPU.BranchType
import mboom.components.TypedDualPortRam

case class BTBParam(n_ways: Int, n_entries: Int)

class BTBCommitEntry extends Bundle {
  val pc      = UInt(addrWidth.W)
  val br_type = UInt(BranchType.width.W)
  val taken   = Bool()
  val bta     = UInt(addrWidth.W)
  val flush   = Bool()
}

class BTBRequest(param: BTBParam) extends Bundle {
  val reqValid = Input(Bool())
  val reqPc    = Input(Vec(param.n_ways, UInt(addrWidth.W)))
  val hit      = Output(Vec(param.n_ways, Bool()))
  val nextPc   = Output(Vec(param.n_ways, UInt(addrWidth.W)))
  val brType   = Output(Vec(param.n_ways, UInt(BranchType.width.W)))
}

class BTBSync(param: BTBParam) extends Module {
  val WaysLen = log2Ceil(param.n_ways)
  val io = IO(new Bundle {
    val request = new BTBRequest(param)
    val commit  = Vec(param.n_ways, Input(Valid(new BTBCommitEntry)))
  })

  val tags = for (_ <- 0 until param.n_ways) yield {
    Module(new TypedDualPortRam(1 << param.n_entries, UInt((29 - param.n_entries).W)))
  }

  val btas = for (_ <- 0 until param.n_ways) yield {
    Module(new TypedDualPortRam(1 << param.n_entries, UInt(addrWidth.W)))
  }

  val br_types = for (_ <- 0 until param.n_ways) yield {
    Module(new TypedDualPortRam(1 << param.n_entries, UInt(BranchType.width.W)))
  }

  def findTag(pc: UInt, hashWidth: Int): UInt = {
    pc(31, hashWidth + 2 + WaysLen)
  }

  def findIndex(pc: UInt, hashWidth: Int): UInt = {
    pc(hashWidth + 1 + WaysLen, 2 + WaysLen)
  }

  val stall = !io.request.reqValid

  //// request
  // Stage 0
  val reqPc = io.request.reqPc
  val reqIndex = reqPc.map(pc => findIndex(pc, param.n_entries))

  val s1Valid  = RegInit(0.B)
  val s1Pc     = RegInit(0.U.asTypeOf(Vec(param.n_ways, UInt(instrWidth.W))))
  val s1Index  = s1Pc.map(pc => findIndex(pc, param.n_entries))
  val s1Tag    = s1Pc.map(pc => findTag(pc, param.n_entries))

  for (i <- 0 until param.n_ways) {
    tags(i).io.wea   := 0.B
    tags(i).io.addra := Mux(stall, s1Index(i), reqIndex(i))
    tags(i).io.dina  := 0.U

    btas(i).io.wea   := 0.B
    btas(i).io.addra := Mux(stall, s1Index(i), reqIndex(i))
    btas(i).io.dina  := 0.U

    br_types(i).io.wea   := 0.B
    br_types(i).io.addra := Mux(stall, s1Index(i), reqIndex(i))
    br_types(i).io.dina  := 0.U
  }

  when (!stall) {
    s1Valid := io.request.reqValid
    s1Pc    := io.request.reqPc
  }

  val btaOut = VecInit(btas.map(r => r.io.douta))
  val brTypeOut = VecInit(br_types.map(r => r.io.douta))

  val s1Hit = VecInit((tags.map(r => r.io.douta) zip s1Tag) map (t => t._1 === t._2))

  for (i <- 0 until param.n_ways) {
    io.request.hit(i)    := s1Hit(i)
    io.request.nextPc(i) := btaOut(i)
    io.request.brType(i) := brTypeOut(i)
  }


  //// commit
  val comUpdate  = io.commit.map(c => c.valid && c.bits.br_type =/= BranchType.None && c.bits.taken)
  val comIndex   = io.commit.map(c => findIndex(c.bits.pc, param.n_entries))
  val comTag     = io.commit.map(c => findTag(c.bits.pc, param.n_entries))
  val comBtas    = io.commit.map(c => c.bits.bta)
  val comBrTypes = io.commit.map(c => c.bits.br_type)

  for (i <- 0 until param.n_ways) {
    tags(i).io.web   := comUpdate(i)
    tags(i).io.addrb := comIndex(i)
    tags(i).io.dinb  := comTag(i)

    btas(i).io.web   := comUpdate(i)
    btas(i).io.addrb := comIndex(i)
    btas(i).io.dinb  := comBtas(i)

    br_types(i).io.web   := comUpdate(i)
    br_types(i).io.addrb := comIndex(i)
    br_types(i).io.dinb  := comBrTypes(i)
  }
}