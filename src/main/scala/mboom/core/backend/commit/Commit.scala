package mboom.core.backend.commit

import chisel3._
import chisel3.util._
import mboom.cache.DCacheReq1
import mboom.core.backend.decode.StoreMode
import mboom.core.backend.rename.RenameCommit
import mboom.config.CPUConfig._
import mboom.cp0.CP0WithCommit
import mboom.core.backend.decode.MDUOp
import mboom.core.backend.decode.InstrType
import mboom.core.backend.lsu.component.SbufferEntry
import mboom.core.backend.mdu.HILOWrite
import mboom.core.frontend.BPU.BranchType
import mboom.core.frontend.{BranchCommitWithBPU, BranchRestoreWithBPU, BranchTrainWithBPU}
import mboom.cp0.CP0Write

class StoreCommit extends Bundle {
  val dCacheReq    = DecoupledIO(new DCacheReq1)
  val unCacheReq   = DecoupledIO(new DCacheReq1)
  val dCacheHazard  = Output(Bool())
  val unCacheHazard = Output(Bool())
}

class Commit(nCommit: Int = 2) extends Module {
  assert(nCommit == 2)

  val io = IO(new Bundle {
    val rob     = Vec(nCommit, Flipped(Decoupled(new ROBEntry)))
    val flush = Input(Bool())

    val commit = Flipped(new RenameCommit(nCommit))
    val branch = Output(new BranchCommitWithBPU)
    val cp0    = Flipped(new CP0WithCommit)

    // val sbRetireReady = Input(Bool())
    val dRetire = Output(Bool())
    val unRetire = Output(Bool())
    // val cp0Write = Output(new CP0Write)
    val hlW      = Output(new HILOWrite)
  })

  val robRaw = io.rob.map(r => r.bits)

  val validMask = WireInit(VecInit(io.rob.map(r => r.valid)))

  val isStore = WireInit(VecInit(robRaw.map(r => r.memWMode =/= StoreMode.disable)))

  val completeMask = Wire(Vec(nCommit, Bool()))

  for (i <- 0 until nCommit) {
    var complete = robRaw(i).complete // && io.rob(i).valid
    for (j <- 0 until i) {
      complete = complete && robRaw(j).complete
    }
    completeMask(i) := complete
  }

  val existMask = WireInit(VecInit((0 to 1).map(i => validMask(i) && completeMask(i))))

  val storeMask = Wire(Vec(nCommit, Bool()))

  for (i <- 0 until nCommit) {
    var hasNoStoreBefore = 1.B
    for (j <- 0 until i) {
      hasNoStoreBefore = hasNoStoreBefore && !isStore(j)
    }
    storeMask(i) := !isStore(i) || (hasNoStoreBefore)
  }

  val flushMask = WireInit(VecInit(Seq.fill(nCommit)(!io.flush)))

  val programException = Wire(Vec(nCommit, Bool()))
  val branchFail       = Wire(Vec(nCommit, Bool()))
  val targetBranchAddr = for (i <- 0 until nCommit) yield {
    robRaw(i).computeBT
  }

  for (i <- 0 until nCommit) {
    programException(i) := existMask(i) && (robRaw(i).exception.asUInt.orR || robRaw(i).eret)
    branchFail(i) := existMask(i) && robRaw(i).branchFail && !robRaw(i).brAlloc
  }

  val restMask = WireInit(VecInit(Seq.fill(nCommit)(1.B)))
  // TODO : SLOT BUG
  // 分支指令和 slot 同时 commit， 避免 clear 流水线时清除 slot，也保证了分支预测训练的正确性
  when((robRaw(0).brType =/= BranchType.None) && !existMask(1)) {
    restMask(0) := 0.B
  }
  when(programException(1)) {
    restMask(1) := 0.B
  }
  when(robRaw(1).brType =/= BranchType.None) {
    restMask(1) := 0.B
  }
  when(programException(0) || io.cp0.potentialInt) {
    restMask(0) := 0.B
    restMask(1) := 0.B
  }

  val finalMask = WireInit(
    VecInit(
      for (i <- 0 until nCommit) yield {
        existMask(i) && storeMask(i) && flushMask(i) && restMask(i)
      }
    )
  )

  // io.rob.ready
  for (i <- 0 to 1) {
    io.rob(i).ready := finalMask(i)
  }

  // [[io.commit]] 数据通路
  for (i <- 0 until nCommit) {
    val logReg = robRaw(i).logicReg
    val oriReg = robRaw(i).originReg
    val phyReg = robRaw(i).physicReg
    val updValid = finalMask(i) && robRaw(i).regWEn

    io.commit.rmt.write(i).addr      := logReg
    io.commit.rmt.write(i).data      := phyReg
    io.commit.freelist.free(i).bits  := oriReg
    // BUG: 潜在bug WAW冲突处理
    io.commit.rmt.write(i).en         := updValid
    io.commit.freelist.free(i).valid  := updValid
  }
  io.commit.rmt.brCommit := finalMask(0) && robRaw(0).brCheck && robRaw(0).brAlloc

  // val branchRecovery = branchFail(0) && completeMask(1) && completeMask(0)

  val branchTrain = WireInit(0.U.asTypeOf(Vec(nCommit, Valid(new BranchTrainWithBPU))))
  for (i <- 0 until nCommit) yield {
    branchTrain(i).valid         := io.rob(i).fire
    branchTrain(i).bits.pc       := robRaw(i).pc
    branchTrain(i).bits.isBranch := (robRaw(i).brType === BranchType.Branch) || (robRaw(i).brType === BranchType.BranchCall)
    branchTrain(i).bits.taken    := robRaw(i).branchTaken
    branchTrain(i).bits.br_type  := robRaw(i).brType
    branchTrain(i).bits.bta      := robRaw(i).computeBT
    branchTrain(i).bits.flush    := 0.B
  }
  io.branch.train := branchTrain

  val pcRestore = WireInit(0.U.asTypeOf(Valid(UInt(addrWidth.W))))
  when(finalMask(0) && finalMask(1) && branchFail(0)) {
    pcRestore.valid := 1.B
    pcRestore.bits  := targetBranchAddr(0)
  }

  val dRetire = WireInit(0.B)
  val unRetire = WireInit(0.B)
  for (i <- 0 until nCommit) yield {
    when(finalMask(i) && isStore(i)) {
      when (robRaw(i).unCache) {
        unRetire := 1.B
      } .otherwise {
        dRetire  := 1.B
      }
    }
  }
  io.dRetire       := dRetire
  io.unRetire      := unRetire

  io.cp0.valid      := validMask(0) && !io.flush
  io.cp0.completed  := robRaw(0).complete
  io.cp0.exceptions := robRaw(0).exception
  io.cp0.eret       := robRaw(0).eret && existMask(0)
  io.cp0.inSlot     := robRaw(0).inSlot
  io.cp0.pc         := robRaw(0).pc
  io.cp0.badvaddr   := robRaw(0).badvaddr

  // mdu: mult div move
  val isMdu     = WireInit(VecInit(robRaw.map(_.instrType === InstrType.mdu)))
  val hiloWrite = WireInit(0.U.asTypeOf(new HILOWrite))
  for(i <- 0 until nCommit) {
    when(finalMask(i) && isMdu(i) && robRaw(i).hiRegWrite.valid) {
      hiloWrite.hi := robRaw(i).hiRegWrite
    }
    when(finalMask(i) && isMdu(i) && robRaw(i).loRegWrite.valid) {
      hiloWrite.lo := robRaw(i).loRegWrite
    }
  }
  val outHiloWrite = RegNext(hiloWrite, 0.U.asTypeOf(new HILOWrite))

  io.hlW      := outHiloWrite

  // branchRestore
  val branchRestore = RegNext(pcRestore, 0.U.asTypeOf(Valid(UInt(32.W))))
  io.branch.restore.address := branchRestore

//  val cp0Write    = WireInit(0.U.asTypeOf(new CP0Write))
//  when (finalMask(0)) {
//    cp0Write.addr   := robRaw(0).cp0Addr
//    cp0Write.sel    := robRaw(0).cp0Sel
//    cp0Write.enable := robRaw(0).cp0RegWrite.valid
//    cp0Write.data   := robRaw(0).cp0RegWrite.bits
//  }
//  io.cp0Write := cp0Write

  // TODO: atomic aexception / interrupt

  //////////////////////////////////////////////////////////////////////
  // 统计性能指标
//  val clockNum = RegInit(0.U(32.W))
//  clockNum := clockNum + 1.U
//  val commitNum     = RegInit(0.U(32.W))
//  val lastCommitNum = RegInit(0.U(32.W))
//  val lsuNum = RegInit(0.U(32.W))
//  val lastlsuNum = RegInit(0.U(32.W))
//  when (io.rob(0).fire && io.rob(1).fire) {
//    commitNum := commitNum + 2.U
//  } .elsewhen(io.rob(0).fire) {
//    commitNum := commitNum + 1.U
//  }
//
//  when (io.rob(0).fire && io.rob(1).fire &&
//    io.rob(0).bits.instrType === InstrType.lsu && io.rob(1).bits.instrType === InstrType.lsu) {
//    lsuNum := lsuNum + 2.U
//  } .elsewhen ((io.rob(0).fire && io.rob(0).bits.instrType === InstrType.lsu) ||
//    (io.rob(1).fire && io.rob(1).bits.instrType === InstrType.lsu)) {
//    lsuNum := lsuNum + 1.U
//  }
//
//  when (clockNum(5, 0) === 0.U) {
//    printf(p"${clockNum}, ${commitNum}($lsuNum), ${commitNum - lastCommitNum}(${lsuNum-lastlsuNum})\n")
//    lastCommitNum := commitNum
//    lastlsuNum := lsuNum
//  }

//  val jumpNum       = RegInit(0.U(32.W))
//  val jumpRight     = RegInit(0.U(32.W))
//
//  val retNum        = RegInit(0.U(32.W))
//  val retRight      = RegInit(0.U(32.W))
//
//  val branchNum     = RegInit(0.U(32.W))
//  val branchRight   = RegInit(0.U(32.W))
//
//  val indirectNum   = RegInit(0.U(32.W))
//  val indirectRight = RegInit(0.U(32.W))
//
//  val branchFailNum = RegInit(0.U(32.W))
//  val branchFailPre = RegInit(0.U(32.W))
//
//  for (i <- 0 until nCommit) {
//    when (io.rob(i).fire) {
//      when(robRaw(i).brType === BranchType.DirectJump || robRaw(i).brType === BranchType.DirectCall) {
//        jumpNum := jumpNum + 1.U
//        when(!robRaw(i).branchFail) {
//          jumpRight := jumpRight + 1.U
//        }
//      }
//
//      when (robRaw(i).brType === BranchType.FuncReturn) {
//        retNum := retNum + 1.U
//        when (!robRaw(i).branchFail) {
//          retRight := retRight + 1.U
//        }
//      }
//
//      when(robRaw(i).brType === BranchType.Branch || robRaw(i).brType === BranchType.BranchCall) {
//        branchNum := branchNum + 1.U
//        when(!robRaw(i).branchFail) {
//          branchRight := branchRight + 1.U
//        }
//      }
//
//      when(robRaw(i).brType === BranchType.IndirectJump || robRaw(i).brType === BranchType.IndirectCall) {
//        indirectNum := indirectNum + 1.U
//        when(!robRaw(i).branchFail) {
//          indirectRight := indirectRight + 1.U
//        }
//      }
//
//      when(robRaw(i).branchFail) {
//        branchFailNum := branchFailNum + 1.U
//        when(robRaw(i).brAlloc) {
//          branchFailPre := branchFailPre + 1.U
//        }
//      }
//    }
//  }
//
//  when (clockNum(7, 0) === 0.U) {
//    printf(p"${clockNum}($commitNum) " +
//      p"jump:${jumpNum}:${jumpRight} " +
//      p"branch:${branchNum}:${branchRight}, " +
//      p"ret:${retNum}:${retRight}, " +
//      p"indirect:${indirectNum}:${indirectRight}, " +
//      p"fail: ${branchFailNum}:${branchFailPre}\n")
//  }

}
