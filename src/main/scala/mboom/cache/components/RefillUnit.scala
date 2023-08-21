package mboom.cache.components

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import mboom.config.CPUConfig._
import mboom.config.CacheConfig
import mboom.axi.AXIIO
import mboom.cache.axi.AXIReadPort

class RefillUnit(AXIID: UInt, unCacheReadSize: Int = 3)(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    val addr = Flipped(ValidIO(UInt(addrWidth.W)))
    val uncache = Input(Bool())

    /** valid信号用来标记数据可用（持续1周期），用于Cache状态机转换 , 改为 Decoupled */
    val data = DecoupledIO(Vec(cacheConfig.numOfBanks, UInt(32.W)))

    val axi = AXIIO.master()
  })
  val axiReadPort  = Module(new AXIReadPort(addrWidth, AXIID, unCacheReadSize)) // TODO AXIID
  val refillBuffer = Module(new ReFillBuffer)
  // outer connection
  io.axi                               <> axiReadPort.io.axi
  io.data                              <> refillBuffer.io.dataOut
  axiReadPort.io.addrReq               := io.addr
  axiReadPort.io.uncache               := io.uncache
  refillBuffer.io.beginBankIndex.valid := io.addr.valid
  refillBuffer.io.beginBankIndex.bits  := cacheConfig.getBankIndex(io.addr.bits)

  // inner connection
  refillBuffer.io.dataIn   := axiReadPort.io.transferData
  refillBuffer.io.dataLast := axiReadPort.io.lastBeat
}
// for icache test
class RefillUnitFaker(AXIID: UInt)(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    val addr = Flipped(ValidIO(UInt(addrWidth.W)))

    /** valid信号用来标记数据可用（持续1周期），用于Cache状态机转换 */
    val data = ValidIO(Vec(cacheConfig.numOfBanks, UInt(32.W)))

    val axi = AXIIO.master()
  })
  val mem = Mem(1024, UInt(dataWidth.W))
  loadMemoryFromFileInline(mem, "test_data/test_axi.in")
  val index = RegInit(0.U(32.W))

  val axiReadPort  = Module(new AXIReadPort(addrWidth, AXIID)) // TODO AXIID
  val refillBuffer = Module(new ReFillBuffer)

  io.axi <> axiReadPort.io.axi
  io.data := refillBuffer.io.dataOut
  axiReadPort.io.addrReq := io.addr
  refillBuffer.io.beginBankIndex.valid := io.addr.valid
  refillBuffer.io.beginBankIndex.bits := cacheConfig.getBankIndex(io.addr.bits)

  val refilling = RegInit(0.B)

  when (io.addr.valid && refilling) {
    index := index + 1.U
  } .elsewhen(io.addr.valid) {
    refilling := 1.B
    index := 0.U
  }

  when (index >= 7.U) {
    refilling := 0.B
  }

  // inner connection
  when (index <= 7.U && io.addr.valid && refilling) {
    printf(p"fetch ${io.addr.bits(31, cacheConfig.bankOffsetLen)+index}:${Hexadecimal(mem(io.addr.bits(31, cacheConfig.bankOffsetLen) + index))}\n")
  }
  refillBuffer.io.dataIn.valid := index <= 7.U && io.addr.valid
  refillBuffer.io.dataIn.bits := mem(io.addr.bits(31, cacheConfig.bankOffsetLen) + index)// axiReadPort.io.transferData
  refillBuffer.io.dataLast := index >= 7.U // axiReadPort.io.lastBeat
}


object RefillUnitGen extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new RefillUnit(0.U)(iCacheConfig), Array("--target-dir", "target/verilog/axi", "--target:fpga"))
}