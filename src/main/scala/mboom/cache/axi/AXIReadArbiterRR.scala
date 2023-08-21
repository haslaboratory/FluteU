package mboom.cache.axi
import chisel3._
import chisel3.util._
import mboom.axi.AXIIO

import scala.collection.immutable
class AXIReadArbiterRR(masterCount: Int) extends Module {
  val io = IO(new Bundle() {
    val masters = Vec(masterCount, AXIIO.slave())
    val bus = AXIIO.master()
  })
  val idle::lock::immutable.Nil = Enum(2)
  val state = RegInit(idle)

  val arSel = RegInit(0.U(log2Ceil(masterCount).W))
  val arValidMask = VecInit(io.masters.map(_.ar.valid))

  io.bus.aw := DontCare
  io.bus.w := DontCare
  io.bus.b := DontCare

  io.bus.ar.bits := io.masters(arSel).ar.bits
  io.bus.ar.valid := io.masters(arSel).ar.valid && state === lock
  io.bus.r.ready := io.masters(arSel).r.ready && state === lock

  for (i <- 0 until masterCount) {
    io.masters(i).ar.ready := Mux(i.U === arSel, io.bus.ar.ready, 0.B) && state === lock
    io.masters(i).r.valid := Mux(i.U === arSel, io.bus.r.valid, 0.B) && state === lock
    io.masters(i).r.bits := io.bus.r.bits

    io.masters(i).aw := DontCare
    io.masters(i).w := DontCare
    io.masters(i).b := DontCare
  }

  val arb = Module(new RRArbiter(UInt(), masterCount))
  for (i <- 0 until masterCount) {
    arb.io.in(i).bits := i.U
    arb.io.in(i).valid := io.masters(i).ar.valid
  }
  arb.io.out.ready := 1.B
  switch(state) {
    is(idle) {
      when(arValidMask.asUInt.orR && arb.io.out.valid) {
//        printf(p"${arValidMask}\n")
        arSel := arb.io.out.bits
//        printf(p"The arSel: ${Hexadecimal(arb.io.out.bits)}\n")
        state := lock
      }
    }

    is(lock) {
      when(io.bus.r.bits.last && io.bus.r.fire) {
        state := idle
      }
    }
  }
}
