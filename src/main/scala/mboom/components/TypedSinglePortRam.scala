package mboom.components

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig

class TypedRamIO[T <: Data](depth: Int, gen: T) extends Bundle {
  require(gen.getWidth > 0)
  val write   = Input(Bool())
  val addr    = Input(UInt(log2Ceil(depth).W))
  val dataIn  = Input(gen)
  val dataOut = Output(gen)
}

class TypedSinglePortRam[T <: Data](depth: Int, gen: T) extends Module {
  val io = IO(new TypedRamIO(depth, gen))

  // remain bugs
  if (CPUConfig.buildVerilog) {
    val width = gen.getWidth
    val mem = Module(new SinglePortBRAM(width, depth))
    mem.io.clk := clock
    mem.io.rst := reset

    mem.io.we   := io.write
    mem.io.addr := io.addr
    mem.io.din  := io.dataIn.asUInt
    io.dataOut  := mem.io.dout.asTypeOf(gen)
  } else {
    // cannot simulate in vivado
    val mem = SyncReadMem(depth, gen)
    io.dataOut := DontCare
    val rdwrPort = mem(io.addr)
    when(io.write) {
      rdwrPort := io.dataIn
    } .otherwise {
      io.dataOut := rdwrPort
    }
    // for simulation in vivado
    //    val mem = RegInit(0.U.asTypeOf(Vec(depth, UInt(width.W))))
    //    val data = RegInit(0.U(width.W))
    //
    //    when(io.write) {
    //      mem(io.addr) := io.dataIn
    //    }
    //    data := mem(io.addr)
    //    io.dataOut := data
  }
}

// using BRAM with no latency in build
class TypedSinglePortAsyncRam[T <: Data](depth: Int, gen: T) extends Module {
  val io = IO(new TypedRamIO(depth, gen))

  // remain bugs
  if (CPUConfig.buildVerilog) {
    val width = gen.getWidth
    val mem = Module(new SinglePortBRAM(width, depth, LATENCY = 0))
    mem.io.clk := clock
    mem.io.rst := reset

    mem.io.we   := io.write
    mem.io.addr := io.addr
    mem.io.din  := io.dataIn.asUInt
    io.dataOut  := mem.io.dout.asTypeOf(gen)
  } else {
    // val ram = Module(new SinglePortRam(depth, width))
    val ram = RegInit(0.U.asTypeOf(Vec(depth, gen)))

    when(io.write) {
      ram(io.addr) := io.dataIn
    }
    io.dataOut := ram(io.addr)
  }
}
