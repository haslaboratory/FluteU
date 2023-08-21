package mboom.util

import chisel3._
import chisel3.util._

import mboom.config.CPUConfig._


object BitPatCombine {

  private def combine(s: Seq[BitPat])(instruction: UInt) = {
    assert(instruction.getWidth == instrWidth)
    s.foldLeft(0.B)((res, bitpat) => res || (instruction === bitpat))
  }
  /**
  判断指令是否为s中的某一个
  */
  def apply(s: Seq[BitPat]) = {
    combine(s) _
  }
}
