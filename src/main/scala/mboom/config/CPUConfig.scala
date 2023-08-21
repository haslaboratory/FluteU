package mboom.config

import chisel3._
import chisel3.util.log2Up

object CPUConfig {
  // Interrupts
  val intrProgramAddr = 0xBFC00380L
  /// amount ///
  val regAmount     = 32 // TODO: to be refractored
  val archRegAmount = 32
  val phyRegAmount  = 64
  //// IBuffer
  val ibufferAmount = 16
  // ROB /////////////////
  val exceptionAmount = 16
  val instrTypeAmount = 8
  val robEntryAmount  = 32
  ////////////////////////
  // SBuffer //////
  val sbufferAmount = 8
  val wbBufferAmount = 4
  val unbufferAmount = 4
  /////////////////
  val issueQEntryMaxAmount = 16

  val decodeWay   = 2

  val fetchGroupSize   = 2
  val fetchGroupWidth  = log2Up(fetchGroupSize)
  val fetchAmountWidth = fetchGroupWidth + 1

  /// width ///
  val instrWidth        = 32
  val dataWidth         = 32
  val addrWidth         = 32
  val byteWidth         = 8
  val regAddrWidth      = 5
  val shamtWidth        = 5
  val iTypeImmWidth     = 16
  val archRegAddrWidth  = log2Up(archRegAmount)
  val phyRegAddrWidth   = log2Up(phyRegAmount)
  val exceptionIdxWidth = log2Up(exceptionAmount)
  val instrTypeWidth    = log2Up(instrTypeAmount)
  val robEntryNumWidth  = log2Up(robEntryAmount)

  val iCacheConfig = CacheConfig(numOfSets = 128, numOfWays = 4, numOfBanks = 8) // 4路组相连 4 * 4KB
  val dCacheConfig = CacheConfig(numOfSets = 128, numOfWays = 2, numOfBanks = 8) // 4路组相连 4 * 4KB

  // added TLB
  val tlbSize           = 8
  val tlbWidth          = log2Up(tlbSize)
  val configTlbSize     = 8
  val configTlbWidth    = log2Up(configTlbSize)
  val numofSearchPort   = 2
  val pageWidth         = 12
  val pageNumWidth      = addrWidth - pageWidth

  // Branch Flush
  val nBranchCount      = 4

  // control parameters
  var buildVerilog = false

  // buffer size
  val hiloCheckpointSize = 8

  // bru
  val enableBru = false
}
