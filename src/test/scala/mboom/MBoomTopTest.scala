package mboom

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sys.process._
import mboom.axi.AXIRam

class FluteTopWrap extends Module {
  val io = IO(new Bundle {
    val hwIntr = Input(UInt(6.W))
    val pc     = Output(UInt(addrWidth.W))
    val arf    = Output(Vec(archRegAmount, UInt(dataWidth.W)))
    val count  = Output(UInt(dataWidth.W))
  })

  val mboomU = Module(new MBoomTop)
  val axiRam: AXIRam = Module(new AXIRam)

  mboomU.io.axi <> axiRam.io.axi

  mboomU.io.hwIntr := io.hwIntr
  io.pc            := mboomU.io.pc
  io.arf           := WireInit(VecInit(Seq.fill(archRegAmount)(0.U(dataWidth.W))))
  io.count         := 0.U
  // io.arf           := fluteU.io.arf
  // io.count         := fluteU.io.count
}

class MBoomTopTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  it should "final" in {
    test(new FluteTopWrap).withAnnotations(Seq(
      WriteVcdAnnotation,
      VerilatorBackendAnnotation)) { c =>
      c.clock.setTimeout(0)
      for (i <- 0 until 80000 * 5) {
        c.io.hwIntr.poke(0.U)
        // println(c.io.pc.peek())
        c.clock.step()
      }
      s"sed -i -e 1,2d test_run_dir/should_final/FluteTopWrap.vcd".!
    }
  }
}
