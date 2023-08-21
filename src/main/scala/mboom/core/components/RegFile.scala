package mboom.core.components

import chisel3._
import mboom.config.CPUConfig._

class RegFile(numRead: Int, numWrite: Int, numExtRead: Int = 0) extends Module {
  val io      = IO(new RegFileIO(numRead, numWrite, numExtRead))
  val regfile = RegInit(VecInit(Seq.fill(phyRegAmount)(0.U(dataWidth.W))))
  for (i <- 0 until numRead) {
    io.read(i).r1Data := regfile(io.read(i).r1Addr)
    io.read(i).r2Data := regfile(io.read(i).r2Addr)
  }
  for (i <- 0 until numWrite) {
    when(io.write(i).writeEnable && (io.write(i).writeAddr =/= 0.U(phyRegAddrWidth.W))) {
      regfile(io.write(i).writeAddr) := io.write(i).writeData
    }
  }
  for (i <- 0 until numExtRead) {
    io.extRead(i).extData := regfile(io.extRead(i).extAddr)
  }
}

class RegFileIO(numRead: Int, numWrite: Int, numExtRead: Int) extends Bundle {
  val read = Vec(numRead, new RegFileReadIO)
  ///
  val write = Vec(numWrite, new RegFileWriteIO)
  ///
  val extRead = Vec(numExtRead, new RegFileExtReadIO)
  // val debug = Output(Vec(phyRegAmount, UInt(dataWidth.W)))
}

class RegFileWriteIO extends Bundle {
  val writeAddr   = Input(UInt(phyRegAddrWidth.W))
  val writeData   = Input(UInt(dataWidth.W))
  val writeEnable = Input(Bool())
}

class RegFileReadIO extends Bundle {
  val r1Addr = Input(UInt(phyRegAddrWidth.W))
  val r2Addr = Input(UInt(phyRegAddrWidth.W))
  val r1Data = Output(UInt(dataWidth.W))
  val r2Data = Output(UInt(dataWidth.W))
}


class RegFileExtReadIO extends Bundle {
  val extAddr = Input(UInt(phyRegAddrWidth.W))
  val extData = Output(UInt(dataWidth.W))
}