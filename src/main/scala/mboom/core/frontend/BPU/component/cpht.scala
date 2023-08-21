package mboom.core.frontend.BPU.component

import chisel3.util._
import chisel3.{Input, _}

case class cphtParams(
                       n_Entries: Int,
                       n_ways: Int,
                     )

class cphtCommit(param: cphtParams) extends Bundle {
  val index: Vec[UInt] = Input(Vec(param.n_ways, UInt(param.n_Entries.W)))
  val ennable: Vec[Bool] = Input(Vec(param.n_ways, Bool()))
  val taken: Vec[UInt] = Input(Vec(param.n_ways, UInt(2.W)))
}

class cphtRequest(param: cphtParams) extends Bundle {
  val index: Vec[UInt] = Input(Vec(param.n_ways, UInt(param.n_Entries.W)))
  val taken: Vec[UInt] = Output(Vec(param.n_ways, UInt(1.W)))
  // taken中 0 是选择第一个，1是选择第二个
}

class cpht(param: cphtParams) extends Module {
  val io = IO(new Bundle {
    val commit = new cphtCommit(param)
    val request = new cphtRequest(param)
  })
  val pht: Vec[UInt] = RegInit(VecInit(Seq.fill(1 << param.n_Entries)(1.U(2.W))))

  def hash(addr: UInt, nEntries: Int): UInt = {
    val hi = WireInit(addr(31, 2))
    var res = WireInit(0.U(nEntries.W))
    for (i <- 0 until (31 / nEntries)) {
      val shift: UInt = (hi >> (i * (31 / nEntries))).asUInt
      res = res ^ shift
    }
    res(nEntries - 1, 0)
  }

  val commitIndex = io.commit.index
  (0 until param.n_ways).foreach(
    i => {
      val index = commitIndex(i)
      io.request.taken(i) := pht(index)(1)
      val state = WireInit(pht(index))
      val commitTaken = WireInit(io.commit.taken(i))
      switch(pht(index)) {
        is(0.U) {
          when(commitTaken(1) >= commitTaken(0)) {
            state := 0.U
          }.otherwise {
            state := 1.U
          }
        }

        is(1.U) {
          when(commitTaken(1) > commitTaken(0)) {
            state := 0.U
          }.elsewhen(commitTaken(1) === commitTaken(0)) {
            state := 1.U
          }.otherwise {
            state := 2.U
          }
        }

        is(2.U) {
          when(commitTaken(1) > commitTaken(0)) {
            state := 1.U
          }.elsewhen(commitTaken(1) === commitTaken(0)) {
            state := 2.U
          }.otherwise {
            state := 3.U
          }
        }

        is(3.U) {
          when(commitTaken(1) <= commitTaken(0)) {
            state := 3.U
          }.otherwise {
            state := 2.U
          }
        }
      }

      when(io.commit.ennable(i)) {
        pht(index) := state
      }
    }
  )

}