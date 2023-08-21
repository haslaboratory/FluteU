package mboom.core.backend.lsu

import chisel3._
import chiseltest._
import mboom.core.backend.utils.AGU
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AGUTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
    "agu should" in {
      test(new AGU) {
        dut => {
          dut.io.prf.r2Data.poke(10.U)
          dut.io.prf.r1Data.poke(8.U)
          dut.io.in.valid.poke(1.B)
//          dut.io.out.bits.baseOp.op2.op.expect(10.U)
//          dut.io.out.bits.baseOp.op1.op.expect(8.U)
        }
      }
    }
}
