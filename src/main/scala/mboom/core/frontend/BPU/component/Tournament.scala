package mboom.core.frontend.BPU.component

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig.addrWidth

case class TourParam(n_ways: Int, global_hist_len: Int, local_hist_len: Int)

class TourCommitEntry extends Bundle {
  val pc = UInt(addrWidth.W)
  val isBranch = Bool()
  val taken = Bool()
}

class TourRequest(param: TourParam) extends Bundle {
  val pc = Input(Valid(UInt(addrWidth.W)))
  val isBranch = Input(Vec(param.n_ways, Bool()))

  val taken = Output(Vec(param.n_ways, Bool()))
  val recover_ghr = Output(UInt(param.global_hist_len.W))
}

// for flush
class TourFlush(param: TourParam) extends Bundle {
  val innerFlush = Input(Bool())
  val extFlush = Input(Bool())
  val recover_ghr = Input(UInt(param.global_hist_len.W))
}
@deprecated
class Tournament(param: TourParam) extends Module {
  val io = IO(new Bundle {
    // val commit = new tourCommit(param)
    val commit = Vec(param.n_ways, Input(Valid(new TourCommitEntry)))
    val request = new TourRequest(param)
    val flush = new TourFlush(param)
  })

  def hash(addr: UInt, nEntries: Int): UInt = {
    val hi = WireInit(addr(31, 2))
    var res = WireInit(0.U(nEntries.W))
    for (i <- 0 until (31 / nEntries)) {
      val shift: UInt = (hi >> (i * (31 / nEntries))).asUInt
      res = res ^ shift
    }
    res(nEntries - 1, 0)
  }

  /////////////////////////////////////////////////////////////////////
  // ghr
  val ghr_r = RegInit(0.U(param.global_hist_len.W)) // redical
  val ghr_c = RegInit(0.U(param.global_hist_len.W)) // conservative
  val gpht = Module(new pht(phtParams(param.global_hist_len, 1)))
  // bht
  val bht = RegInit(VecInit(Seq.fill(1 << (param.local_hist_len + 2))(0.U(param.local_hist_len.W))))
  val pht = Module(new pht(phtParams(param.local_hist_len, 1)))
  // cpht
  val cpht = Module(new cpht(cphtParams(param.global_hist_len, 1)))

  // ------------------------------request ----------------------------
  // only one branch to select !!!!
  val reqValidMask = WireInit(VecInit(for (i <- 0 until param.n_ways)
    yield io.request.pc.valid && io.request.isBranch(i)))
  // If we have several branches (???), we take the first one
  val reqHasBranch = reqValidMask.reduce(_ || _)
  val reqBranchIdx = PriorityMux(reqValidMask, VecInit(for (i <- 0 until param.n_ways) yield i.U(32.W)))
  val taken = WireInit(VecInit(Seq.fill(param.n_ways)(0.B)))

  val pc = io.request.pc.bits
  var local_ghr_r = WireInit(ghr_r)
  // local
  val pcHash = hash(pc + reqBranchIdx * 4.U(32.W), param.local_hist_len + 2)
  val lphtIndex = (pc + reqBranchIdx * 4.U(32.W)) (param.local_hist_len - 1, 0) ^ bht(pcHash)
  pht.io.request.index(0) := lphtIndex
  // global
  val cgphtIndex = hash(pc + reqBranchIdx * 4.U(32.W), param.global_hist_len) ^ local_ghr_r
  gpht.io.request.index(0) := cgphtIndex
  // cpht
  cpht.io.request.index(0) := cgphtIndex

  val raw_taken = Mux(cpht.io.request.taken(0) === 0.U(1.W),
    pht.io.request.taken(0), gpht.io.request.taken(0))

  taken(reqBranchIdx) := reqHasBranch && raw_taken

  local_ghr_r = Mux(reqHasBranch,
    Cat(local_ghr_r(param.global_hist_len - 2, 0), raw_taken), local_ghr_r)

  io.request.recover_ghr := ghr_r
  io.request.taken := taken

  //------------------------------commit---------------------------------
  // ** only one FailBranch in commit, but can be more Branch in commit width **
  // may need optimization
  var local_ghr_c = WireInit(ghr_c)
  val comValidMask = WireInit(VecInit(for (i <- 0 until param.n_ways)
    yield io.commit(i).valid && io.commit(i).bits.isBranch))
  val comHasBranch = comValidMask.reduce(_ || _)
  val comBranchIdx = PriorityMux(comValidMask, VecInit(for (i <- 0 until param.n_ways) yield i.U(32.W)))

  val entry = io.commit(comBranchIdx)
  // local
  val pcHash_c = hash(entry.bits.pc, param.local_hist_len + 2)
  val phtIndex_c = entry.bits.pc(param.local_hist_len - 1, 0) ^ bht(pcHash_c)

  pht.io.commit.index(0) := phtIndex_c
  pht.io.commit.isBranch(0) := entry.valid && entry.bits.isBranch
  pht.io.commit.taken(0) := entry.bits.taken

  when(entry.valid && entry.bits.isBranch) {
    bht(pcHash_c) := Cat(bht(pcHash_c)(param.local_hist_len - 2, 0), entry.bits.taken)
  }
  // global
  val cgphtIndex_c = hash(entry.bits.pc, param.global_hist_len) ^ local_ghr_c

  gpht.io.commit.index(0) := cgphtIndex_c
  gpht.io.commit.isBranch(0) := entry.valid && entry.bits.isBranch
  gpht.io.commit.taken(0) := entry.bits.taken

  // cpht
  val l_suc = entry.bits.taken === pht.io.commit.old_taken(0)
  val g_suc = entry.bits.taken === gpht.io.commit.old_taken(0)

  cpht.io.commit.index(0) := cgphtIndex_c
  cpht.io.commit.ennable(0) := entry.valid && entry.bits.isBranch
  cpht.io.commit.taken(0) := Cat(g_suc, l_suc)

  local_ghr_c = Mux(entry.bits.isBranch && entry.valid,
    Cat(local_ghr_c(param.global_hist_len - 2, 0), entry.bits.taken), local_ghr_c)

  // process slot, only update when the first is taken
  ghr_r := MuxCase(local_ghr_r, Seq(
    io.flush.extFlush -> local_ghr_c,
    io.flush.innerFlush -> io.flush.recover_ghr
  ))
  ghr_c := local_ghr_c

  /////////////////////////////////////////////////////////////////////

}
