//package mboom.core.frontend.BPU
//
//import chisel3._
//import chiseltest._
//import mboom.core.frontend.BPU.component._
//import org.scalatest.freespec.AnyFreeSpec
//import org.scalatest.matchers.should.Matchers
//
//class LHPTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
//  "lhp shouble pass" in {
//    test(new LHP1(LHP1Param(2, 8))) {
//      lhp => {
//        lhp.io.commit(0).valid.poke(1.B)
//        lhp.io.commit(0).bits.pc.poke(4.U)
//        lhp.io.commit(0).bits.isBranch.poke(1.B)
//        lhp.io.commit(0).bits.taken.poke(1.B)
//        lhp.io.commit(1).valid.poke(0.B)
//        lhp.io.commit(1).bits.pc.poke(8.U)
//        lhp.io.commit(1).bits.isBranch.poke(0.B)
//
////        lhp.clock.step()
////
////        lhp.io.commit(0).valid.poke(1.B)
////        lhp.io.commit(0).bits.pc.poke(4.U)
////        lhp.io.commit(0).bits.isBranch.poke(1.B)
////        lhp.io.commit(1).valid.poke(0.B)
////        lhp.io.commit(1).bits.pc.poke(8.U)
////        lhp.io.commit(1).bits.isBranch.poke(0.B)
//
//        lhp.clock.step()
//
//        lhp.io.commit(0).valid.poke(1.B)
//        lhp.io.commit(0).bits.pc.poke(4.U)
//        lhp.io.commit(0).bits.isBranch.poke(1.B)
//        lhp.io.commit(0).bits.taken.poke(0.B)
//        lhp.io.commit(1).valid.poke(0.B)
//        lhp.io.commit(1).bits.pc.poke(8.U)
//        lhp.io.commit(1).bits.isBranch.poke(0.B)
//
//        lhp.io.request.pc.valid.poke(1.B)
//        lhp.io.request.pc.bits.poke(0.U)
//        lhp.io.request.taken(0).expect(0.B)
//        lhp.io.request.taken(1).expect(1.B)
//
//        lhp.clock.step()
//        lhp.io.request.pc.valid.poke(1.B)
//        lhp.io.request.pc.bits.poke(0.U)
//        lhp.io.request.taken(0).expect(0.B)
//        lhp.io.request.taken(1).expect(0.B)
//
//      }
//    }
//  }
//}
