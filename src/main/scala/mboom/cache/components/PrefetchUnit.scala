package mboom.cache.components

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.config.CPUConfig._
import mboom.config.CacheConfig

class PrefetchBuffer(config: CacheConfig) extends Bundle {
  val addr  = RegInit(0.U.asTypeOf(Vec(config.numOfPrefetch, UInt(config.lineAddrLen.W))))
  val data  = RegInit(0.U.asTypeOf(Vec(config.numOfPrefetch, Vec(config.numOfBanks, UInt(dataWidth.W)))))
  val valid = RegInit(0.U.asTypeOf(Vec(config.numOfPrefetch, Bool())))
  val ptr   = RegInit(0.U.asTypeOf(UInt(log2Ceil(config.numOfPrefetch).W)))
}

// n + 1 prefetch
class PrefetchUnit(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
    }))

    val query   = Valid(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
      val data = Vec(cacheConfig.numOfBanks, UInt(dataWidth.W))
      val prefetching = Bool()
    })

    val axi = AXIIO.master()
  })

  val buffer = new PrefetchBuffer(cacheConfig)

  val refillUnit = Module(new RefillUnit(AXIID = 0.U))

  val idle :: waiting :: Nil = Enum(2)
  val state = RegInit(idle)

  val hitWay = WireInit(VecInit(
    for (i <- 0 until cacheConfig.numOfPrefetch) yield {
      io.query.bits.addr === buffer.addr(i) && buffer.valid(i)
    }
  ))

  val hit = hitWay.reduce(_ || _)

  val reqPrefetch = state === idle && io.request.fire && hit
  when (reqPrefetch) {
    buffer.addr(buffer.ptr)  := io.request.bits.addr
    buffer.valid(buffer.ptr) := 1.B
  }

  refillUnit.io.addr.valid := reqPrefetch
  refillUnit.io.addr.bits  := Cat(io.request.bits.addr, 0.U((32-cacheConfig.lineAddrLen).W))

  switch (state) {
    is (idle) {
      when (reqPrefetch) {
        state := waiting
      }
    }

    is (waiting) {

    }
  }

}
