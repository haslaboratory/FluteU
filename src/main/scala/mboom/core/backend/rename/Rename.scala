package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.core.backend.decode.MicroOp
import mboom.config.CPUConfig._

class RenameEntry extends Bundle {
  val srcL      = UInt(phyRegAddrWidth.W)
  val srcR      = UInt(phyRegAddrWidth.W)
  val originReg = UInt(phyRegAddrWidth.W)
  val writeReg  = UInt(phyRegAddrWidth.W)
  val brMask    = UInt((nBranchCount+1).W)
  val brTag     = UInt(log2Ceil(nBranchCount).W)
  val brAlloc   = Bool()

}

class RenameCommit(nCommit: Int) extends Bundle {
  val freelist = new FreelistCommit(nCommit)
  val rmt      = new RMTCommit(nCommit)
}


class RenameOp extends Bundle {
  val uop    = new MicroOp
  val rename = new RenameEntry
}
//class Rename(nWays: Int, nCommit: Int) extends Module {
//  assert(nWays == 2)
//  assert(nCommit == 2)
//  val io = IO(new Bundle {
//    val decode   = Vec(nWays, Flipped(DecoupledIO(new MicroOp)))
//    val dispatch = Vec(nWays, DecoupledIO(new RenameOp))
//
//    val checkIn = Output(Vec(nWays, Valid(UInt(phyRegAddrWidth.W))))
//    // commit
//    val commit = new RenameCommit(nCommit)
//
//    // val stall    = Input(Bool())
//
//    // val rmtDebug = new RMTDebugOut
//  })
//
//  val freelist = Module(new Freelist(nWays, nCommit))
//  val rat      = Module(new RMT(nWays, nCommit))
//
//  val ideal = Wire(Vec(nWays, new RenameEntry))
//  val real  = Wire(Vec(nWays, new RenameEntry))
//  val uops  = Wire(Vec(nWays, new MicroOp))
//
//  uops := io.decode.map(d => d.bits) // raw microOp before renaming
//
//  val allocReq  = io.decode.map(d => (d.valid && d.bits.regWriteEn))
//  val allocResp = VecInit(freelist.io.allocPregs.map(_.valid))
//
//  val allocOffset = WireInit(0.U.asTypeOf(Vec(nWays, UInt(log2Ceil(nWays).W))))
//  for (i <- 1 until nWays) {
//    for (j <- 0 until i) {
//      when (allocReq(j)) {
//        allocOffset(i) := 1.U
//      }
//    }
//  }
//
//  // ideal way
//  for (i <- 0 until nWays) {
//    // RAT read
//    rat.io.read(i)(0).addr := io.decode(i).bits.writeRegAddr
//    rat.io.read(i)(1).addr := io.decode(i).bits.rsAddr
//    rat.io.read(i)(2).addr := io.decode(i).bits.rtAddr
//
//    ideal(i).srcL      := rat.io.read(i)(1).data
//    ideal(i).srcR      := rat.io.read(i)(2).data
//    ideal(i).originReg := rat.io.read(i)(0).data
//    ideal(i).writeReg  := freelist.io.allocPregs(allocOffset(i)).bits
//  }
//
//  // RAW Check
//  real(0).srcL := ideal(0).srcL
//  real(0).srcR := ideal(0).srcR
//
//  for (i <- 1 until nWays) {
//    val fireL    = Wire(Vec(i, Bool()))
//    val fireR    = Wire(Vec(i, Bool()))
//    val writeReg = Wire(Vec(i, UInt(phyRegAddrWidth.W)))
//    for (j <- 0 until i) {
//      fireL(j)    := uops(j).regWriteEn && uops(j).writeRegAddr === uops(i).rsAddr
//      fireR(j)    := uops(j).regWriteEn && uops(j).writeRegAddr === uops(i).rtAddr
//      writeReg(j) := ideal(j).writeReg
//    }
//    // 注意倒序 match case
//    real(i).srcL := MuxCase(
//      ideal(i).srcL,
//      for (j <- i - 1 to 0 by -1) yield { fireL(j) -> writeReg(j) }
//    )
//
//    real(i).srcR := MuxCase(
//      ideal(i).srcR,
//      (i - 1 to 0 by -1).map(j => (fireR(j) -> writeReg(j)))
//    ) // same mean diffrent expression
//  }
//
//  // WAW Check
//
//  // RAT Write Check
//  // why is illegal?
//  val wRATen = Wire(Vec(nWays, Bool()))
//  for (i <- 0 until nWays) {
//    var writeEn = uops(i).regWriteEn
//    for (j <- i + 1 until nWays) {
//      val legal = !(uops(j).regWriteEn && (uops(j).writeRegAddr === uops(i).writeRegAddr) && io.decode(j).valid)
//      writeEn = writeEn && legal
//    }
//    wRATen(i) := writeEn
//  }
//
//  // Origin Reg Check
//  real(0).originReg := ideal(0).originReg
//  for (i <- 1 until nWays) {
//    val fire    = Wire(Vec(i, Bool()))
//    val phyWReg = Wire(Vec(i, UInt(phyRegAddrWidth.W)))
//    for (j <- 0 until i) { //  0 <= j < i
//      fire(j) := uops(j).regWriteEn && uops(j).writeRegAddr === uops(i).writeRegAddr
//      // no need to && uop(i).regWriteEn, think why :)
//      phyWReg(j) := real(j).writeReg
//    }
//
//    real(i).originReg := MuxCase(
//      ideal(i).originReg,
//      for (j <- i - 1 to 0 by -1) yield { fire(j) -> phyWReg(j) }
//    )
//  }
//
//  for (i <- 0 until nWays) {
//    real(i).writeReg := ideal(i).writeReg
//  }
//
//  // 重要的信号: freelist.io.requests, rat.io.write.en 均会改变机器状态（推测态; io.stallReq 会影响流水其他部分
//  // 因此这些信号的生成要注意正确结合流水线上下行的 valid/ready 信号
//
//  // check if enough to allocate
//
//  val freeStall = Wire(Vec(nWays, Bool()))
//  val dispatchStall = Wire(Vec(nWays, Bool()))
//  for (i <- 0 until nWays) {
//    freeStall(i) := allocReq(i) && !freelist.io.allocPregs(allocOffset(i)).valid
//    dispatchStall(i) := io.decode(i).valid && !io.dispatch(i).ready
//  }
//  val stallReq = freeStall.reduce(_ | _)
//  val stall    = dispatchStall.reduce(_ | _)
//
//  val deqCount = WireInit(0.U(log2Ceil(nWays+1).W))
//  deqCount := Mux(!stallReq && !stall, PopCount(allocReq), 0.U)
//  freelist.io.deqCount := deqCount
//
//  for (i <- 0 until nWays) {
//    // 当且仅当 来源数据有效 且 能够分配一组资源 且 外部无暂停信号，正式进行请求
//    val valid = io.decode(i).valid && !stallReq && !stall
//
//    io.dispatch(i).valid        := valid
//    io.dispatch(i).bits.uop     := uops(i)
//    io.dispatch(i).bits.rename  := real(i)
//
//    rat.io.write(i).en      := valid && wRATen(i)
//
//    rat.io.write(i).addr := uops(i).writeRegAddr
//    rat.io.write(i).data := real(i).writeReg
//  }
//
//  for (i <- 0 until nWays) {
//    io.checkIn(i).valid := i.U < deqCount
//    io.checkIn(i).bits  := freelist.io.allocPregs(i).bits
//  }
//
//  // io.stallReq := stallReq
//  for (i <- 0 until nWays) {
//    io.decode(i).ready := !stallReq && !stall
//  }
//
//  // Commit signals
//  for (i <- 0 until nWays) {
//    freelist.io.commit   := io.commit.freelist
//    rat.io.commit        := io.commit.rmt
//    freelist.io.chToArch := io.commit.chToArch
//    rat.io.chToArch      := io.commit.chToArch
//  }
//
//
//}
