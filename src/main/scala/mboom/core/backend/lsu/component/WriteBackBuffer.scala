package mboom.core.backend.lsu.component

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

@deprecated
class WriteBackBuffer extends Module {
  assert(isPow2(wbBufferAmount) && wbBufferAmount > 1)
  private val wbPtrWidth = log2Up(wbBufferAmount)
  val io = IO(new Bundle {
    val write     = Flipped(Decoupled(new SbufferEntry))
    val read      = new SbufferRead
    val sNum      = Output(UInt((wbPtrWidth+2).W))
    val headEntry = Decoupled(new SbufferEntry)
  })

  val entries = RegInit(VecInit(Seq.fill(wbBufferAmount)(0.U.asTypeOf(new SbufferEntry))))

  val headPtr   = RegInit(0.U(wbPtrWidth.W))
  val tailPtr   = RegInit(0.U(wbPtrWidth.W))
  val curNumber = tailPtr - headPtr
  val hasRoom   = curNumber < (wbBufferAmount - 1).U

  val writeFire   = io.write.fire
  val writeEntry  = io.write.bits
  val nextHeadPtr = (headPtr + 1.U)(wbPtrWidth-1, 0)

  // write
  when (writeFire) {
    entries(tailPtr) := writeEntry
    tailPtr          := tailPtr + 1.U
  }
  // read
  val (readData, readValid) = WbUtils.readResult(
    io.read.memGroupAddr,
    entries,
    headPtr,
    tailPtr
  )
  // to dCache
  val headEntry = entries(headPtr)

  when (io.headEntry.fire) {
    headPtr := nextHeadPtr
  }
  // think twice
  io.write.ready := hasRoom.asBool

  io.read.data  := readData
  io.read.valid := readValid

  io.sNum := curNumber

  io.headEntry.valid := (curNumber > 0.U)
  io.headEntry.bits  := headEntry

}

object WbUtils {
  def reorder(
                 entries: Vec[SbufferEntry],
                 head: UInt,
                 tail: UInt,
               ): Vec[SbufferEntry] = {
    val wbPtrWidth = log2Up(wbBufferAmount)
    assert(head.getWidth == wbPtrWidth)
    assert(tail.getWidth == wbPtrWidth)

    val result = WireInit(VecInit(Seq.fill(wbBufferAmount)(0.U.asTypeOf(new SbufferEntry))))
    val curNumber = tail - head

    for (i <- 0 until wbBufferAmount) {
      when(i.U < curNumber) {
        result(i) := entries((i.U + head) (wbPtrWidth - 1, 0))
      }
    }

    result
  }

  def readResult(
                    memGroupAddr: UInt,
                    entries: Vec[SbufferEntry],
                    head: UInt,
                    tail: UInt,
                  ): (UInt, Vec[Bool]) = {
    val data = WireInit(VecInit(Seq.fill(4)(0.U(8.W))))
    val valid = WireInit(VecInit("b0000".U(4.W).asBools))

    val reOrdered = reorder(entries, head, tail)
    for (i <- 0 until wbBufferAmount) {
      for (byte <- 0 until 4) {
        when(reOrdered(i).addr === memGroupAddr && reOrdered(i).valid(byte)) {
          valid(byte) := 1.B
          data(byte) := reOrdered(i).data(8 * byte + 7, 8 * byte)
        }
      }
    }

    (Cat(data(3), data(2), data(1), data(0)), valid)
  }
}