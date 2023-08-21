package mboom.cache.axi

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.ChiselScalatestTester
import mboom.axi.AXIRam
import mboom.config.CPUConfig._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AXIWrapper extends Module {
  val io = IO(new Bundle {

  })
  val axiRam = Module(new AXIRam)
  val axiBankRead = Module(new AXIBankRead(1.U)(dCacheConfig))
  val axiBankWrite = Module(new AXIBankWrite(1.U)(dCacheConfig))

  val data = RegInit(VecInit(1.U(32.W), 2.U(32.W), 3.U(32.W), 4.U(32.W), 5.U(32.W), 6.U(32.W), 7.U(32.W), 8.U(32.W)))
  val addr = 64.U(32.W)

  val state = RegInit(0.U(32.W))

  axiBankWrite.io.req.valid := (state === 0.U)
  axiBankWrite.io.req.bits.addr := addr
  axiBankWrite.io.req.bits.data := data

  axiBankRead.io.req.valid := (state === 2.U)
  axiBankRead.io.req.bits := addr

  switch (state) {
    is (0.U) {
      state := 1.U
    }
    is (1.U) {
      when (axiBankWrite.io.resp) {
        state := 2.U
      }
    }
    is (2.U) {
      state := 3.U
    }

    is (3.U) {
      when (axiBankRead.io.resp.valid) {
        for (i <- 0 until 8) {
          printf(p"${Hexadecimal(axiBankRead.io.resp.bits(i))}, ")
        }
        printf(p"\n")
        state := 4.U
      }
    }
  }

  axiRam.io.axi.ar <> axiBankRead.io.axi.ar
  axiRam.io.axi.r <> axiBankRead.io.axi.r
  axiRam.io.axi.aw <> axiBankWrite.io.axi.aw
  axiRam.io.axi.w <> axiBankWrite.io.axi.w
  axiRam.io.axi.b <> axiBankWrite.io.axi.b

  axiBankRead.io.axi.aw := DontCare
  axiBankRead.io.axi.w := DontCare
  axiBankRead.io.axi.b := DontCare
  axiBankWrite.io.axi.ar := DontCare
  axiBankWrite.io.axi.r := DontCare
}

class AXIWrapperTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
  "test axi" in {
    test(new AXIWrapper).withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)) {
      c => {
        for (i <- 0 until 100) {
          c.clock.step()
        }

      }
    }
  }
}
