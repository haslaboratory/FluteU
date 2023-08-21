package mboom.core.backend.alu
import chisel3._
import chisel3.util._
import firrtl.Utils.True
class AluSelect extends Module{

  val io = IO(new Bundle() {
      val in = Input(UInt(4.W))
      val out = Output(Vec(2, UInt(2.W)))
  })
  io.out := selectFirstN(io.in, 2)
  def selectFirstN(in: UInt, n: Int) = {
    assert(n == 2)
    val sels = Wire(Vec(n, UInt(log2Ceil(in.getWidth).W)))
    sels(0) := PriorityEncoder(in)
    val mask = in - (1.U << sels(0)).asUInt
    sels(1) := PriorityEncoder(mask)
    sels
  }
}
