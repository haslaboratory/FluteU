//package mboom.core.frontend.BPU.component
//
//import chisel3._
//import chiseltest._
//import org.scalatest.freespec.AnyFreeSpec
//import org.scalatest.matchers.should.Matchers
//
//class BTBTest extends AnyFreeSpec with ChiselScalatestTester with Matchers {
//	"btb should pass" in {
//		test(new BTB(BTBParam(1, 4))) {
//			btb => {
//				btb.io.commit(0).valid.poke(1.B)
//				btb.io.commit(0).bits.pc.poke(1.U)
//				btb.io.commit(0).bits.br_type.poke(1.U)
//				btb.io.commit(0).bits.bta.poke(11.U)
//				btb.io.commit(0).bits.taken.poke(1.B)
//				btb.clock.step()
//				btb.io.request.pc.valid.poke(1.B)
//				btb.io.request.pc.bits.poke(1.U)
////				btb.clock.step()
//				btb.io.request.hit(0).expect(1.B)
//				btb.io.request.br_type(0).expect(1.U)
//
//			}
//		}
//	}
//}
//
