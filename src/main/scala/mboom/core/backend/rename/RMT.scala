package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
/**
  * RMT重命名映射表的读端口
  * 
  * addr: 逻辑寄存器地址
  * 
  * data：物理寄存器地址
  */
class RMTReadPort extends Bundle {
  val addr = Input(UInt(archRegAddrWidth.W))
  val data = Output(UInt(phyRegAddrWidth.W))
}

/**
  * RMT重命名映射表写端口
  * 
  * en：使能端
  * 
  * addr：逻辑寄存器地址
  * 
  * data：物理寄存器地址
  */
class RMTWritePort extends Bundle {
  val en   = Input(Bool())
  val addr = Input(UInt(archRegAddrWidth.W))
  val data = Input(UInt(phyRegAddrWidth.W))
}

/**
  * 
  *
  * @param numCommit 写入端口的数目
  */
class RMTCommit(numCommit: Int) extends Bundle {
  val write    = Vec(numCommit, new RMTWritePort)
  val brCommit = Input(Bool())
}

class RMTDebugOut extends Bundle {
  val sRAT = Output(Vec(archRegAmount, UInt(phyRegAddrWidth.W)))
  val aRAT = Output(Vec(archRegAmount, UInt(phyRegAddrWidth.W)))
}

// Map: arch -> phy
/**
  * 
  *
  * @param numWays  在实现中为两路
  * @param numCommit
  * @param release
  */
class RMT(numWays: Int, numCommit: Int, release: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // 每条指令需要三个读端口 0->dest;1->src1;2->src2
    val read = Vec(numWays, Vec(3, new RMTReadPort))

    // 每条指令需要一个写端口
    val write = Vec(numWays, new RMTWritePort)

    // commit to aRAT
    val commit = new RMTCommit(numCommit)

    val chToArch = Input(Bool())

    // val debug = if (!release) Some(new RMTDebugOut) else None
  })

  // init $i -> $i
  val sRAT = RegInit(VecInit(
    for (i <- 0 until archRegAmount) yield {
      i.U(phyRegAddrWidth.W)
    }
  ))
  val aRAT = RegInit(VecInit(
    for (i <- 0 until archRegAmount) yield {
      i.U(phyRegAddrWidth.W)
    }
  ))

  // sRAT
  for (i <- 0 until numWays; tp <- 0 until 3) {
    val archRegAddr = io.read(i)(tp).addr
    val phyRegAddr  = sRAT(archRegAddr)
    io.read(i)(tp).data := phyRegAddr
  }

  for (i <- 0 until numWays) {
    val en          = io.write(i).en
    val archRegAddr = io.write(i).addr
    val phyRegAddr  = io.write(i).data
    when(en && !io.chToArch && archRegAddr =/= 0.U) {
      sRAT(archRegAddr) := phyRegAddr
    }
  }

  // aRAT
  val nextARat = WireInit(aRAT)
  for (i <- 0 until numCommit) {
    val en          = io.commit.write(i).en
    val archRegAddr = io.commit.write(i).addr
    val phyRegAddr  = io.commit.write(i).data
    when(en && archRegAddr =/= 0.U) {
      nextARat(archRegAddr) := phyRegAddr
    }
  }
  aRAT := nextARat

  // aRAT -> sRAT
  when(io.chToArch) {
    sRAT := nextARat
  }



}
