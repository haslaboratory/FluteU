package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO

class AXIWriteArbiterRR(masterCount: Int) extends Module {
  val io = IO(new Bundle {
    val masters = Vec(masterCount, AXIIO.slave())
    val bus     = AXIIO.master()
  })

  val idle :: lock :: Nil = Enum(2)

  val state = RegInit(idle)
  val awSel = RegInit(0.U(log2Ceil(masterCount).W))

  val awValidMask = VecInit(io.masters.map(_.aw.valid))

  val arb = Module(new RRArbiter(UInt(), masterCount))
  for (i <- 0 until masterCount) {
    arb.io.in(i).bits := i.U
    arb.io.in(i).valid := io.masters(i).aw.valid
  }

  io.bus.ar := DontCare
  io.bus.r  := DontCare
  for (i <- 0 until masterCount) {
    when(i.U === awSel && state === lock) {
      io.masters(i).aw <> io.bus.aw
      io.masters(i).w <> io.bus.w
      io.masters(i).b <> io.bus.b
    }.otherwise {
      io.masters(i).aw.ready := 0.B
      io.masters(i).w.ready  := 0.B
      io.masters(i).b.valid  := 0.B
      io.masters(i).b.bits   := DontCare
    }
  }
  when(state === idle) {
    io.bus.aw.valid := 0.B
    io.bus.aw.bits  := DontCare
    io.bus.w.valid  := 0.B
    io.bus.w.bits   := DontCare
    io.bus.b.ready  := 1.B
  }

  arb.io.out.ready := 1.B
  switch(state) {
    is(idle) {
      when (awValidMask.asUInt.orR&&arb.io.out.valid) {
        awSel := arb.io.out.bits
        state := lock
      }
    }

    is(lock) {
      when(io.bus.w.bits.last && io.bus.w.fire) {
        state := idle
      }
    }
  }

}
