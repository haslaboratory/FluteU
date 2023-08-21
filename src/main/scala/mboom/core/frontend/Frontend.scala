package mboom.core.frontend

import chisel3._
import chisel3.util._
import mboom.cache.ICacheWithCore1
import mboom.config.CPUConfig.addrWidth

class Frontend(nWays: Int = 2) extends Module {
  assert(nWays == 2)

  val io = IO(new Bundle {
    val out          = Vec(nWays, DecoupledIO(new IBEntryNew))
    val pc           = Output(UInt(addrWidth.W))
    val branchCommit = Input(new BranchCommitWithBPU)
    val cp0          = Input(new FetchWithCP0)

    val icache = Flipped(new ICacheWithCore1)
  })

  val fetch = Module(new FetchComplexv2)

  fetch.io.iCache <> io.icache
  io.out <> fetch.io.withDecode.ibufferEntries
  fetch.io.cp0          := io.cp0
  fetch.io.branchCommit := io.branchCommit

  io.pc := fetch.io.pc
}