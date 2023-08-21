package mboom.components

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig

// have one latency
class TypedDualPortRam[T <: Data](depth: Int, gen: T) extends Module {
  val io = IO(new Bundle {
    val wea   = Input(Bool())
    val addra = Input(UInt(log2Ceil(depth).W))
    val dina  = Input(gen)
    val douta = Output(gen)

    val web   = Input(Bool())
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dinb  = Input(gen)
    val doutb = Output(gen)
  })

  if (CPUConfig.buildVerilog) {
    val width = gen.getWidth
    val bram = Module(new DualPortBRAM(width, depth))
    val newData = RegInit(0.U.asTypeOf(Valid(gen)))
    newData.valid := (io.wea || io.web) && io.addra === io.addrb
    newData.bits  := Mux(io.wea, io.dina, io.dinb)

    bram.io.clk := clock
    bram.io.rst := reset

    bram.io.wea := io.wea
    bram.io.addra := io.addra
    bram.io.dina := io.dina.asUInt
    // io.douta := Mux(newData.valid, newData.bits, bram.io.douta.asTypeOf(gen))
    io.douta := bram.io.douta.asTypeOf(gen)

    bram.io.web := io.web
    bram.io.addrb := io.addrb
    bram.io.dinb := io.dinb.asUInt
    // io.doutb :=  Mux(newData.valid, newData.bits, bram.io.doutb.asTypeOf(gen))
    io.doutb := bram.io.doutb.asTypeOf(gen)

  } else {
    // For test in chisel
    val ram = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(gen))))
    val addra = RegInit(0.U(log2Ceil(depth).W))
    val addrb = RegInit(0.U(log2Ceil(depth).W))
    val wea   = RegInit(0.B)
    val web   = RegInit(0.B)
    val dina  = RegInit(0.U.asTypeOf(gen))
    val dinb  = RegInit(0.U.asTypeOf(gen))


    addra := io.addra
    wea   := io.wea
    dina  := io.dina
    io.douta := ram(addra)
    when(wea) {
      ram(addra) := dina
    }

    addrb := io.addrb
    web   := io.web
    dinb  := io.dinb
    io.doutb := ram(addrb)
    when(web) {
      ram(addrb) := dinb
    }
  }
}

class TypedDualPortByteWriteRam(depth: Int) extends Module {
  val gen = UInt(32.W)
  val io = IO(new Bundle {
    // val wea   = Input(Bool())
    val addra = Input(UInt(log2Ceil(depth).W))
    val dina  = Input(gen)
    val douta = Output(gen)

    val web   = Input(Vec(4, Bool()))
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dinb  = Input(gen)
    val doutb = Output(gen)
  })

  if (CPUConfig.buildVerilog) {
    val width = gen.getWidth
    val bram = Module(new DualPortByteWriteBRAM(width, depth))
    val writeData  = RegInit(0.U.asTypeOf(gen))
    val writeValid = RegInit(0.U.asTypeOf(Vec(4, Bool())))

    writeData  := io.dinb
    writeValid := VecInit(io.web.map(_ && io.addra === io.addrb))

    bram.io.clk := clock
    bram.io.rst := reset

    bram.io.wea   := 0.U(4.W)
    bram.io.addra := io.addra
    bram.io.dina  := io.dina

    val retData = Seq.fill(4)(WireInit(0.U(8.W)))
    for (i <- 0 until 4) {
      retData(i) := Mux(writeValid(i), writeData(8*i+7, 8*i), bram.io.douta(8*i+7, 8*i))
    }

    io.douta      := Cat(retData.reverse)

    bram.io.web   := Cat(io.web(3), io.web(2), io.web(1), io.web(0))
    bram.io.addrb := io.addrb
    bram.io.dinb  := io.dinb
    io.doutb      := bram.io.doutb
  } else {
    // For test in chisel
    val ram = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(gen))))
    val addra = RegInit(0.U(log2Ceil(depth).W))
    val addrb = RegInit(0.U(log2Ceil(depth).W))


    addra := io.addra
    io.douta := ram(addra)

    addrb := io.addrb
    io.doutb := ram(addrb)

    val writeData = Seq.fill(4)(WireInit(0.U(8.W)))
    for (i <- 0 until 4) {
      writeData(i) := Mux(io.web(i), io.dinb(8*i+7, 8*i), ram(io.addrb)(8*i+7, 8*i))
    }
    ram(io.addrb) := Cat(writeData.reverse)
  }
}

class TypedDualPortAsyncRam[T <: Data](depth: Int, gen: T) extends Module {
  val io = IO(new Bundle {
    val wea   = Input(Bool())
    val addra = Input(UInt(log2Ceil(depth).W))
    val dina  = Input(gen)
    val douta = Output(gen)

    val web   = Input(Bool())
    val addrb = Input(UInt(log2Ceil(depth).W))
    val dinb  = Input(gen)
    val doutb = Output(gen)
  })


  if (CPUConfig.buildVerilog) {
    val width = gen.getWidth
    val bram = Module(new DualPortBRAM(width, depth, 0))
    bram.io.clk := clock
    bram.io.rst := reset

    bram.io.wea := io.wea
    bram.io.addra := io.addra
    bram.io.dina := io.dina.asUInt
    io.douta := bram.io.douta.asTypeOf(gen)

    bram.io.web := io.web
    bram.io.addrb := io.addrb
    bram.io.dinb := io.dinb.asUInt
    io.doutb := bram.io.doutb.asTypeOf(gen)
  } else {
    // For test in chisel
    val ram = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(gen))))

    io.douta := ram(io.addra)
    when (io.wea) {
      ram(io.addra) := io.dina
    }

    io.doutb := ram(io.addrb)
    when(io.web) {
      ram(io.addrb) := io.dinb
    }
  }
}
