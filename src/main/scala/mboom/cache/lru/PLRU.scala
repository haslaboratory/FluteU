package mboom.cache.lru
import chisel3._
import chisel3.util._
import mboom.components.{TypedSinglePortAsyncRam, TypedDualPortAsyncRam}
import mboom.config.CPUConfig._

class Update(nSets: Int, nWays: Int) extends Bundle {
  val index = UInt(log2Ceil(nSets).W)
  val way   = UInt(log2Ceil(nWays).W)
}

class GetLRU(nSets: Int, nWays: Int) extends Bundle {
  val index = Input(UInt(log2Ceil(nSets).W))
  val way   = Output(UInt(log2Ceil(nWays).W))
  val valid = Input(Bool())
}
class PLRU(nSets: Int, numOfWay: Int) extends Module {
  val io = IO(new Bundle() {
    val update = Flipped(ValidIO(new Update(nSets, numOfWay)))
    val getLRU = new GetLRU(nSets, numOfWay)
  })

//  val lruReg      = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(3)(false.B)))))
  val updateIndex =  WireInit(io.update.bits.index)
  val updateWay   = WireInit(io.update.bits.way)

  val ReqIndex    = WireInit(io.getLRU.index)
  val ReqWay      = WireInit(0.U)

  if (numOfWay == 2) {
    val lruReg = Module(new TypedDualPortAsyncRam(nSets, Bool()))

    // update
    lruReg.io.wea   := io.update.valid
    lruReg.io.addra := updateIndex
    lruReg.io.dina  := (updateWay === 0.U)
    // query
    lruReg.io.web   := 0.B
    lruReg.io.addrb := ReqIndex
    lruReg.io.dinb  := 0.U

    ReqWay := lruReg.io.doutb
  } else {
    val lruReg =
      for (j <- 0 until 3) yield
        Module(new TypedDualPortAsyncRam(nSets, Bool()))

    // update
    lruReg(0).io.wea   := io.update.valid
    lruReg(0).io.addra := updateIndex
    lruReg(0).io.dina  := (updateWay <= 1.U)

    lruReg(1).io.wea   := io.update.valid && (updateWay <= 1.U)
    lruReg(1).io.addra := updateIndex
    lruReg(1).io.dina  := (updateWay === 0.U)

    lruReg(2).io.wea   := io.update.valid && (updateWay > 1.U)
    lruReg(2).io.addra := updateIndex
    lruReg(2).io.dina  := (updateWay === 2.U)
    // query
    lruReg(0).io.web   := 0.B
    lruReg(0).io.addrb := ReqIndex
    lruReg(0).io.dinb  := 0.U
    lruReg(1).io.web   := 0.B
    lruReg(1).io.addrb := ReqIndex
    lruReg(1).io.dinb  := 0.U
    lruReg(2).io.web   := 0.B
    lruReg(2).io.addrb := ReqIndex
    lruReg(2).io.dinb  := 0.U

    when(lruReg(0).io.doutb) {
      ReqWay := Mux(lruReg(2).io.doutb, 3.U, 2.U)
    }.otherwise {
      ReqWay := Mux(lruReg(1).io.doutb, 1.U, 0.U)
    }
  }

  io.getLRU.way := ReqWay

  // 对PLRU进行更新
//  when(io.update.valid) {
//    //        printf(p"Update the ${io.update.bits.way} index: ${io.update.bits.index}\n")
//    if (numOfWay == 2) {
//      lruReg(updateIndex)(0) := (updateWay === 0.U)
//    } else {
//      //          printf(p"update the lruReg${updateIndex} ${lruReg(updateIndex)}\n")
//      when(updateWay <= 1.U) {
//        lruReg(updateIndex)(0) := 1.B
//        lruReg(updateIndex)(1) := (updateWay === 0.U)
//      }.otherwise {
//        lruReg(updateIndex)(0) := 0.B
//        lruReg(updateIndex)(2) := (updateWay === 2.U)
//      }
//    }
//  }
//  when(io.getLRU.valid) {
//    if (numOfWay == 2) {
//
//      ReqWay := Mux(lruReg(Reqindex)(0), 1.U, 0.U)
//    } else {
//      when(lruReg(Reqindex)(0)) {
//        ReqWay := Mux(lruReg(Reqindex)(2), 3.U, 2.U)
//      }.otherwise {
//        ReqWay := Mux(lruReg(Reqindex)(1), 1.U, 0.U)
//      }
//    }
//    //      printf(p"LRU the ${ReqWay}\n")
//  }
}
