package mboom.cache.components

import chisel3.Module
import chisel3._
import chisel3.tester.{testableClock, testableData}
import chisel3.util.ValidIO
import chiseltest.{ChiselScalatestTester, VerilatorBackendAnnotation, WriteVcdAnnotation}
import mboom.axi.AXIRam
import mboom.config.CacheConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RefillUnitWrapper extends Module {
	val io = IO(new Bundle() {
		val data = ValidIO(Vec(8, UInt(32.W)))
		val addr = Flipped(ValidIO(UInt(32.W)))
	})
	val refillUnit = Module(new RefillUnit(AXIID = 0.U)(CacheConfig()))
	val axiRam = Module(new AXIRam)
	refillUnit.io.addr.bits := io.addr.bits
	refillUnit.io.addr.valid := io.addr.valid
	refillUnit.io.axi <> axiRam.io.axi
	io.data := refillUnit.io.data
}
// The test data
//0000000f
//0000000d
//0000000b
//00000001
//0000000b
//08000001
//00001000
//000000ff
//000000ef
//00000008
//00000012
//00000054
//00000034
//00000013
//00000076
//000000f9

class RefillUnit_test extends AnyFreeSpec with ChiselScalatestTester with Matchers{
	"test refillunit should" in {
		test(new RefillUnitWrapper).withAnnotations(Seq(WriteVcdAnnotation, VerilatorBackendAnnotation)) {
			dut => {
				dut.io.addr.valid.poke(1.B)
				dut.io.addr.bits.poke(0.U)
				dut.clock.step(4)
				dut.io.data.bits(0).expect(15.U)
				dut.clock.step()
				dut.io.data.bits(1).expect(13.U)
				dut.clock.step()
				dut.io.data.bits(2).expect(11.U)
				dut.io.data.valid.expect(0.B)
				dut.clock.step(5)
				dut.io.data.valid.expect(1.B)

				dut.clock.step()
				dut.io.addr.valid.poke(1.B)
				dut.io.addr.bits.poke(8.U)

				// 前两个都是正确的
				dut.clock.step(8)
				dut.io.data.bits(0).expect(4096.U)
				dut.io.data.valid.expect(0.B)

				dut.clock.step()
				dut.io.data.bits(1).expect(255.U)
				dut.io.data.valid.expect(1.B)

				// should be 8.U，开始错误
				dut.clock.step()
				dut.io.data.bits(2).expect(8.U)
				dut.io.data.valid.expect(1.B)

			}
		}
	}
}
