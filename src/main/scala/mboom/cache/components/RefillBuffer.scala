package mboom.cache.components

import chisel3._
import chisel3.util._
import mboom.config.CacheConfig
import mboom.config.CPUConfig._

class ReFillBuffer(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    ///  in ///
    val dataIn         = Flipped(ValidIO(UInt(32.W)))
    val dataLast       = Input(Bool())
    // valid指示refillBuffer状态机进入refilling态
    val beginBankIndex = Flipped(ValidIO(UInt(cacheConfig.bankIndexLen.W)))

    /// out ///
    // 当整个Cacheline有效时，valid为高，持续一周期, 改为Decoupled
    val dataOut = DecoupledIO(Vec(cacheConfig.numOfBanks, UInt(32.W)))
  })
  val buffer = RegInit(
    VecInit(Seq.fill(cacheConfig.numOfBanks)(0.U(32.W)))
  )
  val index = RegInit(0.U(cacheConfig.bankIndexLen.W))

  val idle :: refilling :: holding :: Nil = Enum(3)
  val state = RegInit(idle)

  switch(state) {
    is(idle) {
      when(io.beginBankIndex.valid) {
        state := refilling
        index := io.beginBankIndex.bits
      }
    }
    is(refilling) {
      when(io.dataIn.valid) {
        // printf(p"buffer $index: ${Hexadecimal(io.dataIn.bits)}\n")
        buffer(index) := io.dataIn.bits
        index := index + 1.U
        state := Mux(io.dataLast, holding, refilling)
      }
    }
    is(holding) {
      when (io.dataOut.ready) {
        state := idle
      }
    }
  }

  io.dataOut.valid := state === holding
  io.dataOut.bits  := buffer
}
