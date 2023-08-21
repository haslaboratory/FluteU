package mboom.core.components

import chisel3._
import chiseltest._
import mboom.core.backend.decode.Controller
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ControllerTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
  "AND Test" in {
    test(new Controller()) { c =>

    }
  }
}
