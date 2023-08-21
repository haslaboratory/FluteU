package mboom.config

import chisel3._
import chisel3.util._

case class CacheConfig(
    numOfSets: Int = 64,
    numOfWays: Int = 2,
    numOfBanks: Int = 8,
    numOfPrefetch: Int = 4,
    bankWidth: Int = 4 // bytes per bank
) {
//  val numSets: Int      = numOfSets
//  val numWays: Int      = numOfWays
//  val numBanks: Int     = numOfBanks
  val indexLen: Int     = log2Ceil(numOfSets)
  val bankIndexLen: Int = log2Ceil(numOfBanks)
  // byte addressable memory, 1 bit -> 1 byte change in memory
  val bankOffsetLen: Int = log2Ceil(bankWidth)
  val tagLen: Int        = 32 - indexLen - bankIndexLen - bankOffsetLen
  val lineAddrLen: Int  = tagLen + indexLen
  require(isPow2(numOfSets))
  require(isPow2(numOfWays))
  require(isPow2(numOfBanks))
  require(isPow2(bankWidth))
  require(tagLen + indexLen + bankIndexLen + bankOffsetLen == 32, "basic request calculation")
  // require((indexLen + bankIndexLen + bankOffsetLen) <= 12, "prevent request aliasing")

  def getBankIndex(addr: UInt) = {
    require(addr.getWidth == 32)
    addr(bankOffsetLen + bankIndexLen - 1, bankOffsetLen)
  }

  def getBankMask():UInt = {
    (~(1.U(bankIndexLen.W))).asUInt
  }

  def getBankMaskv2(): UInt = {
    (~(3.U(bankIndexLen.W))).asUInt
  }
  def getIndex(addr: UInt) = {
    require(addr.getWidth == 32)
    addr(bankOffsetLen + bankIndexLen + indexLen - 1, bankOffsetLen + bankIndexLen)
  }
  def getTag(addr: UInt) = {
    require(addr.getWidth == 32)
    addr(31, 31 - tagLen + 1)
  }

  def getLineAddr(addr: UInt) = {
    require(addr.getWidth == 32)
    addr(31, 31 - tagLen - indexLen + 1)
  }

  def firstLast(addr: UInt):Bool = {
    // require(addr.getWidth == 32)
    addr(bankOffsetLen + bankIndexLen - 1, bankOffsetLen).andR
  }

  def secondLast(addr: UInt):Bool = {
    // require(addr.getWidth == 32)
    addr(bankOffsetLen + bankIndexLen - 1, bankOffsetLen+1).andR && !addr(bankOffsetLen)
  }
}
