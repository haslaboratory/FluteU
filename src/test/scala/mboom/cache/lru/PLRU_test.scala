package mboom.cache.lru

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.ChiselScalatestTester
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PLRU_m_test extends AnyFreeSpec with ChiselScalatestTester with Matchers {
  "plru_m of two ways should " in {
    test(new PLRU(3, 2)) { dut =>
      {
        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(0.U)
        dut.io.getLRU.index.poke(0.U)
        dut.clock.step()

        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(0.U)
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.index.poke(0.U)
        dut.io.getLRU.way.expect(1.U)

        dut.clock.step()

        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(1.U)
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.index.poke(0.U)
        dut.io.getLRU.way.expect(1.U)

        dut.clock.step()

        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.index.poke(0.U)
        dut.io.getLRU.way.expect(0.U)
      }
    }
  }
  "plru_m of four ways should " in {
    test(new PLRU(3, 4)) { dut =>
      {
        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(0.U)

        dut.clock.step()
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.way.expect(2.U)
        dut.io.getLRU.index.poke(0.U)

        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(2.U)

        dut.clock.step()
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.way.expect(1.U)
        dut.io.getLRU.index.poke(0.U)

        dut.clock.step()
        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(0.U)

        dut.clock.step()
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.way.expect(3.U)
        dut.io.getLRU.index.poke(0.U)

        dut.clock.step()
        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(0.U)
        dut.io.update.bits.way.poke(0.U)

        dut.clock.step()
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.way.expect(3.U)
        dut.io.getLRU.index.poke(0.U)

        dut.clock.step()
        dut.io.update.valid.poke(1.B)
        dut.io.update.bits.index.poke(1.U)
        dut.io.update.bits.way.poke(0.U)

        dut.clock.step()
        dut.io.getLRU.valid.poke(1.B)
        dut.io.getLRU.way.expect(3.U)
        dut.io.getLRU.index.poke(0.U)
      }
    }
  }
}
