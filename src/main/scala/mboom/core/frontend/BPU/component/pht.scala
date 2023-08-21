package mboom.core.frontend.BPU.component

import chisel3._

case class phtParams(
                      n_Entries: Int,
                      n_ways: Int,
                    )

class phtCommit(param: phtParams) extends Bundle {
  val index: Vec[UInt] = Input(Vec(param.n_ways, UInt(param.n_Entries.W)))
  val isBranch: Vec[Bool] = Input(Vec(param.n_ways, Bool()))
  val taken: Vec[Bool] = Input(Vec(param.n_ways, Bool()))

  val old_taken: Vec[Bool] = Output(Vec(param.n_ways, Bool()))
}

class phtRequest(param: phtParams) extends Bundle {
  val index: Vec[UInt] = Input(Vec(param.n_ways, UInt(param.n_Entries.W)))
  val taken: Vec[Bool] = Output(Vec(param.n_ways, Bool()))
}

class pht(params: phtParams) extends Module {
  val io = IO(new Bundle {
    val commit = new phtCommit(params)
    val request = new phtRequest(params)
  })
  val pht: Vec[UInt] = RegInit(VecInit(Seq.fill(1 << params.n_Entries)(1.U(2.W))))
  // 使用高位判断是否进行跳转
  (0 until params.n_ways).foreach(i => io.request.taken(i) := pht(io.request.index(i))(1))

  //  进行更新
  (0 until params.n_ways).foreach(i => {
    io.commit.old_taken(i) := pht(io.commit.index(i))(1)
    when(io.commit.isBranch(i)) {
      val cnt = pht(io.commit.index(i))
      val taken = io.commit.taken(i)
      val newCnt = Mux(taken, cnt + 1.U, cnt - 1.U)
      val wen = (taken && (cnt =/= "b11".U)) || (!taken && (cnt =/= "b00".U))
      when(wen) {
        pht(io.commit.index(i)) := newCnt
      }
    }
  })
}
