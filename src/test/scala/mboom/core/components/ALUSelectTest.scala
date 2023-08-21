package mboom.core.components

import mboom.util.BitMode.fromIntToBitModeLong
import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import mboom.config.CPUConfig._
import mboom.core.backend.alu.AluSelect
import mboom.core.components.ALU

import scala.util.Random

class ALUSelectTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
  "select should" in {
    test(new AluSelect) {
      select => {
        select.io.in.poke(6.U)
        select.io.out(1).expect(2.U)
        select.io.out(0).expect(1.U)
      }
    }
  }
}
