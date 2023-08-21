package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

object BranchRestoreUtils {
  def generateRestoreMask(b: UInt, n: UInt): UInt = {
    assert(b.getWidth == 5)
    assert(n.getWidth == 5)

    val res = MuxCase(0.U(5.W), Seq(
      (b === 1.U && n === 2.U)  -> "b00010".U(5.W),
      (b === 1.U && n === 4.U)  -> "b00110".U(5.W),
      (b === 1.U && n === 8.U)  -> "b01110".U(5.W),
      (b === 1.U && n === 16.U) -> "b11110".U(5.W),
      (b === 2.U && n === 4.U)  -> "b00100".U(5.W),
      (b === 2.U && n === 8.U)  -> "b01100".U(5.W),
      (b === 2.U && n === 16.U) -> "b11100".U(5.W),
      (b === 2.U && n === 1.U)  -> "b11101".U(5.W),
      (b === 4.U && n === 8.U)  -> "b01000".U(5.W),
      (b === 4.U && n === 16.U) -> "b11000".U(5.W),
      (b === 4.U && n === 1.U)  -> "b11001".U(5.W),
      (b === 4.U && n === 2.U)  -> "b11011".U(5.W),
      (b === 8.U && n === 16.U) -> "b10000".U(5.W),
      (b === 8.U && n === 1.U)  -> "b10001".U(5.W),
      (b === 8.U && n === 2.U)  -> "b10011".U(5.W),
      (b === 8.U && n === 4.U)  -> "b10111".U(5.W),
      (b === 16.U && n === 1.U) -> "b00001".U(5.W),
      (b === 16.U && n === 2.U) -> "b00011".U(5.W),
      (b === 16.U && n === 4.U) -> "b00111".U(5.W),
      (b === 16.U && n === 8.U) -> "b01111".U(5.W),
    ))
    res
  }
}

class BranchRestore(nBrCount: Int) extends Module {
  val io = IO(new Bundle {
    val missPred = Input(Bool())
    val brMask   = Input(UInt((nBrCount+1).W))
    val brTag    = Input(UInt(log2Ceil(nBrCount).W))
    val bta      = Input(UInt(addrWidth.W))

    val nowMask   = Input(UInt((nBrCount+1).W))

    val restore  = Output(Bool())
    val outTag   = Output(UInt(log2Ceil(nBrCount).W))
    val outMask  = Output(UInt((nBrCount+1).W))
    val outBta   = Output(UInt(addrWidth.W))
  })

  val restore = RegInit(0.B)
  val brTag  = RegInit(0.U(log2Ceil(nBrCount).W))
  val brMask = RegInit(0.U((nBrCount+1).W))
  val bta     = RegInit(0.U(addrWidth.W))

  restore := io.missPred
  brTag   := io.brTag
  brMask  := BranchRestoreUtils.generateRestoreMask(io.brMask, io.nowMask)
  bta     := io.bta

  io.restore := restore
  io.outTag  := brTag
  io.outMask := brMask
  io.outBta  := bta
}
