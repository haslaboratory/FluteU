package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.cache.CacheInvalidate
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.alu.AluEntry
import mboom.core.components.HiLoIO
import mboom.core.backend.alu.AluWB
import mboom.core.backend.alu.AluPipelineUtil
import mboom.core.components.RegFileReadIO
import mboom.core.backend.decode._
import mboom.core.components.StageReg
import mboom.core.backend.alu.BypassBundle
import mboom.cp0.{CP0Read, CP0TLBReq, CP0Write, ExceptionBundle}
import mboom.mmu.{TLBEntry, TLBReq, TLBWithCore}
import mboom.core.backend.commit.ROBCompleteBundle
import mboom.util.ValidBundle
import mboom.core.backend.utils._

class MduExecute extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MicroMDUOp))
    val wb = Output(new AluWB)

    val cp0Read  = Flipped(new CP0Read)
    val cp0Write = Output(new CP0Write)
    val hiloCommit = Input(new HILOWrite)

    val tlbReq    = Flipped(new TLBReq)
    val cp0TLBReq = ValidIO(new CP0TLBReq)

    val icacheInv = Flipped(new CacheInvalidate)
    val dcacheInv = Flipped(new CacheInvalidate)

    val flush = Input(new ExecFlush(nBranchCount))
  })

  val cp0Execute     = Module(new CP0Execute)
  val multDivExecute = Module(new MultDivExecute)
  val tlbExecute     = Module(new TLBExecute)
  val cacheExecute   = Module(new CacheExecute)

  io.in.ready :=
    cp0Execute.io.in.ready && multDivExecute.io.in.ready &&
      tlbExecute.io.in.ready && cacheExecute.io.in.ready

  cp0Execute.io.in.valid     := io.in.valid && MduExecuteUtil.isCp0(io.in.bits.mduOp)
  multDivExecute.io.in.valid := io.in.valid && MduExecuteUtil.isMultDiv(io.in.bits.mduOp)
  tlbExecute.io.in.valid     := io.in.valid && MduExecuteUtil.isTlb(io.in.bits.mduOp)
  cacheExecute.io.in.valid   := io.in.valid && MduExecuteUtil.isCache(io.in.bits.mduOp)

  cp0Execute.io.in.bits     := io.in.bits
  multDivExecute.io.in.bits := io.in.bits
  tlbExecute.io.in.bits     := io.in.bits
  cacheExecute.io.in.bits   := io.in.bits

  cp0Execute.io.cp0Read <> io.cp0Read
  io.cp0Write           := cp0Execute.io.cp0Write

  tlbExecute.io.tlbReq    <> io.tlbReq
  tlbExecute.io.cp0TLBReq <> io.cp0TLBReq

  io.dcacheInv <> cacheExecute.io.dcacheInv
  io.icacheInv <> cacheExecute.io.icacheInv

  // flush
  multDivExecute.io.flush := io.flush

  // Stage 3: WriteBack
  val stage = Module(new StageReg(Valid(new MduWB)))
  stage.io.flush := ExecuteUtil.needFlush(io.flush, io.in.bits.baseOp)
  stage.io.valid := 1.B // enable

  stage.io.in.valid := io.in.valid &&
    (cp0Execute.io.out.valid || multDivExecute.io.out.valid ||
      tlbExecute.io.out.valid || cacheExecute.io.out.valid)
  stage.io.in.bits := MuxCase(
    0.U.asTypeOf(new MduWB),
    Seq(
      cp0Execute.io.out.valid     -> cp0Execute.io.out.bits,
      multDivExecute.io.out.valid -> multDivExecute.io.out.bits,
      tlbExecute.io.out.valid     -> tlbExecute.io.out.bits,
      cacheExecute.io.out.valid   -> cacheExecute.io.out.bits
    )
  )

  io.wb.rob             := stage.io.data.bits.rob
  io.wb.rob.valid       := stage.io.data.valid
  io.wb.prf.writeAddr   := stage.io.data.bits.prf.writeAddr
  io.wb.prf.writeData   := stage.io.data.bits.prf.writeData
  io.wb.prf.writeEnable := stage.io.data.bits.prf.writeEnable && stage.io.data.valid
  io.wb.busyTable.valid := stage.io.data.bits.prf.writeEnable
  io.wb.busyTable.bits  := stage.io.data.bits.prf.writeAddr

  multDivExecute.io.hiloWrite.brMask   := stage.io.data.bits.brMask
  multDivExecute.io.hiloWrite.hi.valid := stage.io.valid && stage.io.data.bits.rob.hiRegWrite.valid
  multDivExecute.io.hiloWrite.hi.bits  := stage.io.data.bits.rob.hiRegWrite.bits
  multDivExecute.io.hiloWrite.lo.valid := stage.io.valid && stage.io.data.bits.rob.loRegWrite.valid
  multDivExecute.io.hiloWrite.lo.bits  := stage.io.data.bits.rob.loRegWrite.bits
  multDivExecute.io.hiloCommit := io.hiloCommit
}

class MduWB extends Bundle {
  val rob = new ROBCompleteBundle(robEntryNumWidth)
  val prf = new Bundle {
    val writeAddr   = UInt(phyRegAddrWidth.W)
    val writeData   = UInt(dataWidth.W)
    val writeEnable = Bool()
  }
  val busyTable = ValidBundle(UInt(phyRegAddrWidth.W))
  val brMask    = UInt((nBranchCount+1).W)
}

class CacheExecute extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MicroMDUOp))
    val out = Valid(new MduWB)

    val icacheInv = Flipped(new CacheInvalidate)
    val dcacheInv = Flipped(new CacheInvalidate)
  })

  val idle :: invalidating :: complete :: Nil = Enum(3)
  val state = RegInit(idle)

  io.in.ready := !io.in.valid || (state === complete)

  val uop = io.in.bits

  io.icacheInv.req.valid := io.in.valid && (state === idle) && uop.cacheOp === CacheOp.inst
  io.dcacheInv.req.valid := io.in.valid && (state === idle) && uop.cacheOp === CacheOp.data

  val vAddr = uop.baseOp.op1.op + uop.immediate
  io.icacheInv.req.bits  := vAddr
  io.dcacheInv.req.bits  := vAddr

  val wb          = WireInit(0.U.asTypeOf(new MduWB))
  val robComplete = WireInit(0.U.asTypeOf(new ROBCompleteBundle))

  robComplete.valid   := 1.B
  robComplete.robAddr := uop.baseOp.robAddr

  wb.rob := robComplete

  // io out
  io.out.valid := (state === complete)
  io.out.bits  := wb

  switch (state) {
    is (idle) {
      when (io.in.valid && (io.icacheInv.req.fire || io.dcacheInv.req.fire)) {
        state := invalidating
      }
    }
    is (invalidating) {
      when (io.icacheInv.resp || io.dcacheInv.resp) {
        state := complete
      }
    }
    is (complete) {
      state := idle
    }
  }
}


// 组合逻辑
class CP0Execute extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MicroMDUOp))
    val out = ValidIO(new MduWB)

    val cp0Read  = Flipped(new CP0Read)
    val cp0Write = Output(new CP0Write)

  })

  val uop = io.in.bits

  io.in.ready := 1.B

  // cp0 Read
  io.cp0Read.addr := uop.cp0RegAddr
  io.cp0Read.sel  := uop.cp0RegSel

  // cp0 write
  io.cp0Write.enable := io.in.valid && uop.mduOp === MDUOp.mtc0
  io.cp0Write.addr   := uop.cp0RegAddr
  io.cp0Write.sel    := uop.cp0RegSel
  io.cp0Write.data   := uop.baseOp.op2.op

  val wb          = WireInit(0.U.asTypeOf(new MduWB))
  val robComplete = WireInit(0.U.asTypeOf(new ROBCompleteBundle))

  val regWData = io.cp0Read.data

  // wb
  robComplete.valid := 1.B
  robComplete.robAddr := uop.baseOp.robAddr

  wb.rob             := robComplete
  wb.prf.writeEnable := uop.baseOp.regWriteEn
  wb.prf.writeAddr   := uop.baseOp.writeRegAddr
  wb.prf.writeData   := regWData
  wb.busyTable.valid := uop.baseOp.regWriteEn
  wb.busyTable.bits  := uop.baseOp.writeRegAddr

  wb.brMask          := io.in.bits.baseOp.brMask

  // io out
  io.out.valid := io.in.valid
  io.out.bits  := wb
}

class TLBExecute extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new MicroMDUOp))
    val out       = ValidIO(new MduWB)

    val tlbReq    = Flipped(new TLBReq)
    val cp0TLBReq = ValidIO(new CP0TLBReq)

    // val flush     = Input(Bool())
  })
  val uop   = io.in.bits
  val read  = RegInit(0.U.asTypeOf(new TLBEntry))
  val probe = RegInit(0.U(32.W))
  val idle :: busy :: Nil = Enum(2)
  val state = RegInit(idle)

  io.in.ready := (state === busy) || !io.in.valid
  // stage 0
  io.tlbReq.write.fromRandom := io.in.fire && io.in.bits.mduOp === MDUOp.tlbwr
  io.tlbReq.write.fromIndex  := io.in.fire && io.in.bits.mduOp === MDUOp.tlbwi
  // stage 1

  read      := io.tlbReq.read.read
  probe     := io.tlbReq.probe.probe

  io.cp0TLBReq.valid      := (state === busy)
  io.cp0TLBReq.bits.tlbOp := uop.mduOp
  io.cp0TLBReq.bits.read  := read
  io.cp0TLBReq.bits.probe := probe

  val wb = WireInit(0.U.asTypeOf(new MduWB))
  val robComplete = WireInit(0.U.asTypeOf(new ROBCompleteBundle))

  robComplete.valid := 1.B
  robComplete.robAddr := uop.baseOp.robAddr

  wb.rob := robComplete

  wb.brMask := io.in.bits.baseOp.brMask

  // io out
  io.out.valid := io.in.fire
  io.out.bits  := wb

  switch (state) {
    is (idle) {
      when (io.in.valid) {
        state := busy
      }
    }
    is (busy) {
      state := idle
    }
  }
}

class DivReqBuf extends Bundle {
  val op1 = UInt(addrWidth.W)
  val op2 = UInt(addrWidth.W)
  val signed = Bool()
}

class MBuf extends Bundle {
  val hilo64 = UInt(64.W)
  val multRes = UInt(64.W)
  val isMsub  = Bool()
}

class MultUnit extends Module {
  val io = IO(new Bundle{
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val sign   = Input(Bool())
    val result = Output(UInt(64.W))
  })

  val part_0   = RegInit(0.U(32.W))
  val part_1   = RegInit(0.U(32.W))
  val part_2   = RegInit(0.U(32.W))
  val part_3   = RegInit(0.U(32.W))

  val a_sign = io.a(31)
  val b_sign = io.b(31)

  val sign     = RegNext(io.sign)
  val out_sign = RegNext(a_sign ^ b_sign)

  val cal_a = Mux(io.sign && a_sign, -io.a, io.a)
  val cal_b = Mux(io.sign && b_sign, -io.b, io.b)

  part_0 := cal_a(15, 0)  * cal_b(15, 0)
  part_1 := cal_a(15, 0)  * cal_b(31, 16)
  part_2 := cal_a(31, 16) * cal_b(15, 0)
  part_3 := cal_a(31, 16) * cal_b(31, 16)

  val mid_result =
    Cat(0.U(32.W), part_0) +
      Cat(0.U(16.W), part_1, 0.U(16.W)) +
      Cat(0.U(16.W), part_2, 0.U(16.W)) +
      Cat(part_3, 0.U(32.W))

  io.result := Mux(sign && out_sign, -mid_result, mid_result)
}
// 组合逻辑
class MultDivExecute extends Module {
  val io = IO(new Bundle {
    val in    = Flipped(Decoupled(new MicroMDUOp))
    val out   = ValidIO(new MduWB)

    // val hiloCanWrite = Output(Bool())

    val hiloWrite  = Input(new HILOWritev2)
    val hiloCommit = Input(new HILOWrite)

    val flush = Input(new ExecFlush(nBranchCount))
  })
  val flushNow = io.in.valid && ExecuteUtil.needFlush(io.flush, io.in.bits.baseOp)

  val idle :: divBusy :: multBusy :: maddsubBusy :: Nil = Enum(4)
  val state = RegInit(idle)

  val hilo = Module(new HILOv1)
  val div  = Module(new DIVBlackBox())
  val mul  = Module(new MultUnit())

  val uop = io.in.bits
  val mduOp = uop.mduOp
  // mfhi mflo mthi mtlo and tlb relevant instruction
  val isMove = (mduOp === MDUOp.mfhi || mduOp === MDUOp.mthi
    || mduOp === MDUOp.mflo || mduOp === MDUOp.mtlo)

  val regWData = MuxLookup(
    io.in.bits.mduOp,
    0.U,
    Seq(
      MDUOp.mfhi -> hilo.io.read.hi,
      MDUOp.mflo -> hilo.io.read.lo,
    )
  )

  // calculate
  val op1 = uop.baseOp.op1.op
  val op2 = uop.baseOp.op2.op
  // The operation is signed
  val signed = uop.mduOp === MDUOp.mul || uop.mduOp === MDUOp.mult || uop.mduOp === MDUOp.div || uop.mduOp === MDUOp.madd || uop.mduOp === MDUOp.msub
  // is mult
  val isMult = uop.mduOp === MDUOp.mult || uop.mduOp === MDUOp.multu || uop.mduOp === MDUOp.mul

  val isMAddSub = uop.mduOp === MDUOp.msub || uop.mduOp === MDUOp.msubu || uop.mduOp === MDUOp.madd || uop.mduOp === MDUOp.maddu

  val isDiv  = uop.mduOp === MDUOp.div  || uop.mduOp === MDUOp.divu

  // val multRes = Mux(signed, (op1.asSInt * op2.asSInt).asUInt, op1 * op2)
  mul.io.a      := op1
  mul.io.b      := op2
  mul.io.sign   := signed
  val multRes = mul.io.result
  val mbuf   = RegInit(0.U.asTypeOf(new MBuf))
  // for madd msub
  //  switch(mstate) {
  //    is(idle) {
  //      when (!flushNow && isMAddSub && io.in.valid) {
  //        mbuf.multRes := multRes
  //        mbuf.hilo64 := Cat(hilo.io.read.hi, hilo.io.read.lo)
  //        mbuf.isMsub := uop.mduOp === MDUOp.msub || uop.mduOp === MDUOp.msubu
  //        mstate := busy
  //      }
  //    }
  //    is(busy) {
  ////      printf(p"multRes: ${Hexadecimal(mbuf.multRes)} ${Hexadecimal(mbuf.hilo64)} ${mbuf.isMsub}\n")
  //      mstate := idle
  //    }
  //  }

  val mAddSubRes = Mux(mbuf.isMsub, mbuf.hilo64 - mbuf.multRes,  mbuf.hilo64 + mbuf.multRes)

  // div为多周期
  val divReqBuf = RegInit(0.U.asTypeOf(new DivReqBuf))

  div.io.rst          := reset
  div.io.clk          := clock
  div.io.signed_div_i := divReqBuf.signed
  div.io.opdata1_i    := divReqBuf.op1
  div.io.opdata2_i    := divReqBuf.op2
  div.io.start_i      := (state === divBusy)
  div.io.annul_i      := flushNow

  val divRes = div.io.result_o
  val divResValid = div.io.ready_o && (state === divBusy)

  val multResValid = (state === multBusy) && isMult
  val moveResValid = io.in.valid && isMove
  val maddsubResValid = (state === maddsubBusy) && isMAddSub
  val resValid = multResValid || divResValid || moveResValid || maddsubResValid
  val res = MuxCase(
    0.U(64.W),
    Seq(
      maddsubResValid -> mAddSubRes,
      multResValid    -> multRes,
      divResValid     -> divRes
    )
  )

  io.in.ready     :=
    (state === idle && !isDiv && !isMAddSub && !isMult) ||
      multResValid || maddsubResValid || divResValid

  val wb = WireInit(0.U.asTypeOf(new MduWB))
  wb.rob.valid            := 1.B
  wb.rob.robAddr          := uop.baseOp.robAddr
  wb.rob.hiRegWrite.valid := MduExecuteUtil.mduWriteHi(mduOp)
  wb.rob.loRegWrite.valid := MduExecuteUtil.mduWriteLo(mduOp)
  wb.rob.hiRegWrite.bits  := Mux(isMove, uop.baseOp.op1.op, res(63, 32))
  wb.rob.loRegWrite.bits  := Mux(isMove, uop.baseOp.op1.op, res(31, 0))

  // for mul instruction
  wb.prf.writeEnable := uop.baseOp.regWriteEn
  wb.prf.writeAddr   := uop.baseOp.writeRegAddr
  wb.prf.writeData   := Mux(isMove, regWData, res(31, 0))

  wb.busyTable.valid := uop.baseOp.regWriteEn
  wb.busyTable.bits  := uop.baseOp.writeRegAddr

  wb.brMask := io.in.bits.baseOp.brMask

  io.out.valid := resValid
  io.out.bits  := wb

  // div state machine
  switch(state) {
    is(idle) {
      when(!flushNow && io.in.valid && isDiv) { // is div
        state := divBusy
        divReqBuf.op2 := io.in.bits.baseOp.op2.op
        divReqBuf.op1 := io.in.bits.baseOp.op1.op
        divReqBuf.signed := signed
      }
      when(!flushNow && io.in.valid && (isMult || isMAddSub)) {
        state := multBusy
      }
    }
    is(divBusy) {
      when(flushNow || div.io.ready_o) {
        state := idle
        divReqBuf := 0.U.asTypeOf(new DivReqBuf)
      }
    }
    is (multBusy) {
      when (!flushNow && isMAddSub) {
        mbuf.multRes := multRes
        mbuf.hilo64 := Cat(hilo.io.read.hi, hilo.io.read.lo)
        mbuf.isMsub := uop.mduOp === MDUOp.msub || uop.mduOp === MDUOp.msubu
        state := maddsubBusy
      } .otherwise {
        state := idle
      }
    }
    is (maddsubBusy) {
      state := idle
    }
  }

  hilo.io.write  := io.hiloWrite
  hilo.io.commit := io.hiloCommit

  hilo.io.flush := io.flush

}

object MduExecuteUtil {
  def isTlb(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.tlbwi || mduOp === MDUOp.tlbwr ||
      mduOp === MDUOp.tlbp || mduOp === MDUOp.tlbr)
  }
  def isCp0(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.mfc0 || mduOp === MDUOp.mtc0)
  }

  def isMultDiv(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.mult || mduOp === MDUOp.multu || mduOp === MDUOp.mul
      || mduOp === MDUOp.div || mduOp === MDUOp.divu
      || mduOp === MDUOp.mfhi || mduOp === MDUOp.mthi
      || mduOp === MDUOp.mflo || mduOp === MDUOp.mtlo
      || mduOp === MDUOp.msub || mduOp === MDUOp.msubu
      || mduOp === MDUOp.maddu || mduOp === MDUOp.madd)
  }

  def isCache(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.cache)
  }

  def mduWriteHi(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.mult || mduOp === MDUOp.multu
      || mduOp === MDUOp.div || mduOp === MDUOp.divu
      || mduOp === MDUOp.mthi || mduOp === MDUOp.maddu
      || mduOp === MDUOp.madd || mduOp === MDUOp.msub
      || mduOp === MDUOp.msubu)
  }

  def mduWriteLo(mduOp: UInt) = {
    require(mduOp.getWidth == MDUOp.width)

    (mduOp === MDUOp.mult || mduOp === MDUOp.multu
      || mduOp === MDUOp.div || mduOp === MDUOp.divu
      || mduOp === MDUOp.mtlo || mduOp === MDUOp.maddu
      || mduOp === MDUOp.madd || mduOp === MDUOp.msub
      || mduOp === MDUOp.msubu)
  }
}
