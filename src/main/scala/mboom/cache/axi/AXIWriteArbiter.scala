package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO

class AXIWriteArbiter(masterCount: Int) extends Module {
  val io = IO(new Bundle {
    val masters = Vec(masterCount, AXIIO.slave())
    val release = Vec(masterCount, Input(Bool()))
    val bus     = AXIIO.master()
  })

  val idle :: transfering :: Nil = Enum(2)

  val state = RegInit(idle)
  val awSel = RegInit(0.U(log2Ceil(masterCount).W))

  val awValidMask = VecInit(io.masters.map(_.aw.valid))

  io.bus.ar := DontCare
  io.bus.r  := DontCare
  for (i <- 0 until masterCount) {
    io.masters(i).ar := DontCare
    io.masters(i).r := DontCare

    io.masters(i).aw.ready := Mux(i.U === awSel, io.bus.aw.ready, 0.B) && state =/= idle
    io.masters(i).w.ready  := Mux(i.U === awSel, io.bus.w.ready, 0.B) && state =/= idle
    io.masters(i).b.valid  := Mux(i.U === awSel, io.bus.b.valid, 0.B) && state =/= idle
    io.masters(i).b.bits   := io.bus.b.bits

  }
  io.bus.aw.bits  := io.masters(awSel).aw.bits
  io.bus.aw.valid := io.masters(awSel).aw.valid && state =/= idle
  io.bus.w.bits   := io.masters(awSel).w.bits
  io.bus.w.valid  := io.masters(awSel).w.valid && state =/= idle
  io.bus.b.ready  := io.masters(awSel).b.ready && state =/= idle

  switch(state) {
    is(idle) {
      when (awValidMask.asUInt.orR) {
        awSel := PriorityEncoder(awValidMask)
        state := transfering
      }
    }

    is(transfering) {
      when (io.release(awSel)) {
        state := idle
      }
    }
  }

}
