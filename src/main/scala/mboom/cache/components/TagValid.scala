package mboom.cache.components

import chisel3._
import mboom.config.CacheConfig
import mboom.components.{TypedRamIO, TypedSinglePortRam}

class TagValidBundle(implicit cacheConfig: CacheConfig) extends Bundle {
  val tag   = UInt(cacheConfig.tagLen.W)
  val valid = Bool()
}

object TagValidBundle {
  def apply(tag: UInt, valid: Bool)(implicit cacheConfig: CacheConfig) = {
    val t = WireInit(0.U.asTypeOf(new TagValidBundle))
    t.tag   := tag
    t.valid := valid
    t
  }
}
class TagValid(implicit cacheConfig: CacheConfig) extends Module {
  val numOfSets = cacheConfig.numOfSets
  val numOfWays = cacheConfig.numOfWays

  val io = IO(new Bundle {
    val select = Vec(numOfWays, new TypedRamIO(numOfSets, new TagValidBundle))
  })

  val dataFiled =
    for (i <- 0 until numOfWays) yield Module(new TypedSinglePortRam(numOfSets, new TagValidBundle))

  for (i <- 0 until numOfWays) {
    io.select(i) <> dataFiled(i).io
  }

}
