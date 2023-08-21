package mboom.cp0

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import chisel3.stage.ChiselStage
import mboom.core.backend.decode.MDUOp
import mboom.mmu.{TLBEntry, TLBWithCP0, WritePort}

class CP0Read extends Bundle {
  val addr = Input(UInt(5.W))
  val sel  = Input(UInt(3.W))
  val data = Output(UInt(dataWidth.W))
}

class CP0Write extends Bundle {
  val addr   = UInt(5.W)
  val sel    = UInt(3.W)
  val data   = UInt(dataWidth.W)
  val enable = Bool()
//  val HiLoBoth = Bool() // 在处理TLBR指令的时候需要同时读写HiLo寄存器
}

class CP0DebugIO extends Bundle {
  val badvaddr = Output(UInt(dataWidth.W))
  val count    = Output(UInt(dataWidth.W))
  val status   = Output(UInt(dataWidth.W))
  val cause    = Output(UInt(dataWidth.W))
  val epc      = Output(UInt(dataWidth.W))
  val compare  = Output(UInt(dataWidth.W))
}

class CP0WithCommit extends Bundle {
  val pc         = Input(UInt(addrWidth.W))
  val inSlot     = Input(Bool()) // whether the instruction in pc is delay slot
  val exceptions = Input(new ExceptionBundle)
  val completed  = Input(Bool())
  val eret       = Input(Bool())
  val valid      = Input(Bool())
  val badvaddr   = Input(UInt(addrWidth.W))

  val potentialInt = Output(Bool())
}

class CP0TLBReq extends Bundle {
  val tlbOp = UInt(MDUOp.width.W)
  val read  = new TLBEntry
  val probe = UInt(32.W)
}
class CP0WithCore extends Bundle {
  val read    = new CP0Read
  val write   = Input(new CP0Write)
  val commit  = new CP0WithCommit
  val eretReq = Output(Bool())
  val intrReq = Output(Bool()) // 例外输出信号
  val excAddr = Output(UInt(dataWidth.W))
  val epc     = Output(UInt(dataWidth.W))

  val tlbReq  = Flipped(Valid(new CP0TLBReq))
}

class CP0 extends Module {
  val io = IO(new Bundle {
    val hwIntr = Input(UInt(6.W))
    val core   = new CP0WithCore
    val tlb    = Flipped(new TLBWithCP0)
    // val debug  = new CP0DebugIO
  })
  // TODO：添加寄存器支持TLB，详见Regs.scala
  //       并且响应TLB对应的exception，涉及对cause、badvaddr、entryhi的修改
  val badvaddr = new CP0BadVAddr
  //       random寄存器实现为计数器实现伪随机
  val count    = new CP0Count
  val status   = new CP0Status
  val cause    = new CP0Cause
  val epc      = new CP0EPC
  val compare  = new CP0Compare
  val countInc = RegInit(0.B)
  val ebase    = new CP0EBase
  val prid     = new CP0PRID
  val context  = new CP0Context
  val config0  = new CP0Config0
  val config1  = new CP0Config1
  val config2  = new CP0Config2
  val taghi    = new CP0TagHi
  val taglo    = new CP0TagLo
  val errorpc  = new CP0ErrorPC
  // added TLB
  val entryHi  = new CP0EntryHi
  val entryLo0 = new CP0EntryLo0
  val entryLo1 = new CP0EntryLo1
  val index    = new CP0Index
  val random   = new CP0Random
  val pageMask = new CP0PageMask
  val wired    = new CP0Wired

  val regs = Seq(
    badvaddr,
    count,
    status,
    cause,
    epc,
    compare,
    ebase,
    prid,
    context,
    config0,
    config1,
    config2,
    taghi,
    taglo,
    errorpc,
    // added TLB
    index,
    entryHi,
    entryLo0,
    entryLo1,
    random,
    pageMask,
    wired
  )

  // cp0 Read
  val readRes = WireInit(0.U(dataWidth.W))
  regs.foreach(r =>
    when(io.core.read.addr === r.addr.U && io.core.read.sel === r.sel.U) {
      readRes := r.reg.asUInt
    }
  )
  io.core.read.data := readRes

  countInc := !countInc

  val commitWire = io.core.commit
  val excVector =
    VecInit(commitWire.exceptions.asUInt.asBools.map(_ && commitWire.valid && commitWire.completed))
      .asTypeOf(new ExceptionBundle)

  val hasExc = excVector.asUInt.orR
  val intReqs = for (i <- 0 until 8) yield {
    cause.reg.ip(i) && status.reg.im(i)
  }

  val potentialInt = intReqs.foldLeft(0.B)((z, a) => z || a) && status.reg.ie && !status.reg.exl && !status.reg.erl
  io.core.commit.potentialInt := potentialInt
  val hasInt = potentialInt && commitWire.valid && commitWire.completed

  when(hasExc) {
    status.reg.exl := 1.B
    when(!status.reg.exl) {
      cause.reg.bd := commitWire.inSlot
      when(excVector.adELi) {
        epc.reg := commitWire.badvaddr
      }.elsewhen(commitWire.inSlot) {
        epc.reg := commitWire.pc - 4.U
      }.otherwise {
        epc.reg := commitWire.pc
      }
    }
    cause.reg.excCode := PriorityMux(
      Seq(
        excVector.adELi -> ExceptionCode.adEL,
        excVector.ri    -> ExceptionCode.ri,
        excVector.ov    -> ExceptionCode.ov,
        excVector.sys   -> ExceptionCode.sys,
        excVector.adELd -> ExceptionCode.adEL,
        excVector.adES  -> ExceptionCode.adEs,
        excVector.bp    -> ExceptionCode.bp,
        excVector.tlblInv -> ExceptionCode.tlbl,
        excVector.tlblRfl -> ExceptionCode.tlbl,
        excVector.tlbsInv -> ExceptionCode.tlbs,
        excVector.tlbsRfl -> ExceptionCode.tlbs,
        excVector.tlbsMod -> ExceptionCode.mod,
        excVector.trap  -> ExceptionCode.trap
      )
    )
  } .elsewhen (hasInt) {
    status.reg.exl := 1.B
    when(!status.reg.exl) {
      cause.reg.bd := commitWire.inSlot
      when(excVector.adELi) {
        epc.reg := commitWire.badvaddr
      }.elsewhen(commitWire.inSlot) {
        epc.reg := commitWire.pc - 4.U
      }.otherwise {
        epc.reg := commitWire.pc
      }
    }
    cause.reg.excCode := ExceptionCode.int
  } .elsewhen(commitWire.eret && commitWire.valid) {
    when (status.reg.erl) {
      status.reg.erl := 0.B
    } .otherwise {
      status.reg.exl := 0.B
    }
  }

  def wReq(r: CP0BaseReg): Bool = {
    // when write cp0, there is no int or exec !!!
    io.core.write.enable && io.core.write.addr === r.addr.U && io.core.write.sel === r.sel.U // && !hasInt && !hasExc
  }

  val tlbMiss = excVector.tlblInv || excVector.tlblRfl ||
    excVector.tlbsInv || excVector.tlbsRfl || excVector.tlbsMod

  val tlbRfl = excVector.tlblRfl || excVector.tlbsRfl

  // badvaddr
  when(excVector.adELd || excVector.adELi || excVector.adES ||
    tlbMiss) {
    badvaddr.reg := commitWire.badvaddr
  }

  // count
  when(wReq(count)) {
    count.reg := io.core.write.data
  }.otherwise {
    count.reg := count.reg + countInc.asUInt
  }

  // status
  val writeStatusWire = WireInit(io.core.write.data.asTypeOf(new StatusBundle))
  when(wReq(status)) {
    status.reg.cu  := writeStatusWire.cu
    status.reg.rp  := writeStatusWire.rp
    status.reg.bev := writeStatusWire.bev
    status.reg.ts  := writeStatusWire.ts
    status.reg.im  := writeStatusWire.im
    status.reg.um  := writeStatusWire.um
    status.reg.erl := writeStatusWire.erl
    status.reg.exl := writeStatusWire.exl
    status.reg.ie  := writeStatusWire.ie
  }

  // cause
  val writeCauseWire = WireInit(io.core.write.data.asTypeOf(new CauseBundle))
  when(wReq(cause)) {
    for (i <- 0 to 1) yield {
      cause.reg.ip(i) := writeCauseWire.ip(i)
    }
    cause.reg.iv := writeCauseWire.iv
  }
  for (i <- 2 to 6) yield {
    cause.reg.ip(i) := io.hwIntr(i - 2)
  }
  when((wReq(count) || wReq(compare)) && !io.hwIntr(5)) {
    cause.reg.ip(7) := 0.B
  }.elsewhen(io.hwIntr(5) || ((count.reg === compare.reg) && (compare.reg =/= 0.U))) {
    cause.reg.ip(7) := 1.B
  }
  when(wReq(count) || wReq(compare)) {
    cause.reg.ti := 0.B
  }.elsewhen(count.reg === compare.reg && compare.reg =/= 0.U) {
    cause.reg.ti := 1.B
  }

  // epc
  when(wReq(epc)) {
    epc.reg := io.core.write.data
  }

  // compare
  when(wReq(compare)) {
    compare.reg := io.core.write.data
  }

  // pagemask
  // refuse write request to pageMask

  // ebase
  val writeEBaseWire = WireInit(io.core.write.data.asTypeOf(new EBaseBundle))
  when (wReq(ebase)) {
    ebase.reg.ebase  := writeEBaseWire.ebase
  }

  // context
  val writeContextWire = WireInit(io.core.write.data.asTypeOf(new ContextBundle))
  // TODO context.BadVpN some part should be write by badvadr
  when (wReq(context)) {
    context.reg.pteBase := writeContextWire.pteBase
  }

  // config0
  val writeConfig0Wire = WireInit(io.core.write.data.asTypeOf(new Config0Bundle))
  when (wReq(config0)) {
    config0.reg.impl := writeConfig0Wire.impl
    config0.reg.k0   := writeConfig0Wire.k0
  }

  // taghi
  when (wReq(taghi)) {
    taghi.reg := io.core.write.data
  }

  // taglo
  when (wReq(taglo)) {
    taglo.reg := io.core.write.data
  }

  // errorpc
  when(wReq(errorpc)) {
    errorpc.reg := io.core.write.data
  }

  // tlb regs
  val tlbReq = io.core.tlbReq.bits
  val tlbValid = io.core.tlbReq.valid
  when(tlbReq.tlbOp === MDUOp.tlbp && tlbValid) {
    index.reg.p     := tlbReq.probe(31)
    index.reg.index := tlbReq.probe(tlbWidth - 1, 0)
  }.elsewhen(tlbReq.tlbOp === MDUOp.tlbr && tlbValid) {
    entryHi.reg.vpn   := tlbReq.read.vpn
    entryHi.reg.asid  := tlbReq.read.asid
    pageMask.reg.mask := 0.U
    Seq(entryLo0.reg, entryLo1.reg)
      .zip(tlbReq.read.pages)
      .foreach(e => {
        e._1.pfn := e._2.pfn
        e._1.cache := e._2.cache
        e._1.valid := e._2.valid
        e._1.dirty := e._2.dirty
        e._1.global := tlbReq.read.global
      })
  } .otherwise {
    when (wReq(index)) {
      val d = io.core.write.data.asTypeOf(new IndexBundle)
      index.reg.index := d.index
    }

    when (wReq(entryHi)) {
      val d = io.core.write.data.asTypeOf(new EntryHiBundle)
      entryHi.reg.asid := d.asid
      entryHi.reg.vpn  := d.vpn
    } .elsewhen(tlbMiss) {
      entryHi.reg.vpn := commitWire.badvaddr(31, 13)
      context.reg.badVPN2 := commitWire.badvaddr(31, 13)
    }

    when (wReq(entryLo0)) {
      val d = io.core.write.data.asTypeOf(new EntryLoBundle)
      entryLo0.reg.pfn    := d.pfn
      entryLo0.reg.cache  := d.cache
      entryLo0.reg.dirty  := d.dirty
      entryLo0.reg.valid  := d.valid
      entryLo0.reg.global := d.global
    }

    when (wReq(entryLo1)) {
      val d = io.core.write.data.asTypeOf(new EntryLoBundle)
      entryLo1.reg.pfn    := d.pfn
      entryLo1.reg.cache  := d.cache
      entryLo1.reg.dirty  := d.dirty
      entryLo1.reg.valid  := d.valid
      entryLo1.reg.global := d.global
    }
  }

  // random / wired
  val writeWiredWire = io.core.write.data.asTypeOf(new WiredBundle)
  when (wReq(wired)) {
    wired.reg.wired   := writeWiredWire.wired
    random.reg.random := (tlbSize - 1).U
  } .elsewhen(tlbReq.tlbOp === MDUOp.tlbwr && tlbValid) {
    random.reg.random := Mux(random.reg.random.andR(), wired.reg.wired, random.reg.random + 1.U)
  }

  io.tlb.regs.pageMask := pageMask.reg
  io.tlb.regs.entryHi  := entryHi.reg
  io.tlb.regs.entryLo0 := entryLo0.reg
  io.tlb.regs.entryLo1 := entryLo1.reg
  io.tlb.regs.index    := index.reg
  io.tlb.regs.random   := random.reg
  io.tlb.regs.kseg0Uncached := config0.reg.k0 === 2.U

  val exceptJumpAddr = Mux(
    status.reg.bev,
    MuxCase(
      "hbfc00380".U,
      Seq(
        tlbRfl                   -> "hbfc00200".U,
        status.reg.exl           -> "hbfc00380".U,
      )
    ),
    Cat(
      ebase.reg.asUInt()(31, 12),
      MuxCase(
        "h180".U(12.W),
        Seq(
          tlbRfl                   -> 0.U(12.W),
          (hasInt && cause.reg.iv) -> "h200".U(12.W),
          status.reg.exl           -> "h180".U(12.W),
        )
      )
    )
  )

  // flush signal
  val intrReq = RegNext(hasExc || hasInt, 0.B)
  val eretReq = RegNext(commitWire.eret && commitWire.valid, 0.B)

  io.core.intrReq := intrReq
  io.core.eretReq := eretReq

  val excAddr = RegNext(exceptJumpAddr, "hbfc00380".U)
  io.core.epc     := Mux(status.reg.erl, errorpc.reg, epc.reg)
  io.core.excAddr := excAddr

}

object CP0Main extends App {
  (new ChiselStage).emitVerilog(new CP0, Array("--target-dir", "target/verilog", "--target:fpga"))
}
