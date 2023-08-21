package mboom.cache.top

import chisel3._
import chisel3.util._
import chiseltest._
import mboom.util.BaseTestHelper
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import mboom.config.CPUConfig._
import mboom.axi.AXIRam
import mboom.cache.{InstrCache}

private class TestHelper(logName: String)
    extends BaseTestHelper(logName, () => new InstrCache(iCacheConfig)) {}

class InstrCacheTest extends AnyFreeSpec with Matchers {
  "empty test" in {
    val testHelper = new TestHelper("instr_cache_empty")
    testHelper.close()
  }
}
