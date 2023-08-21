package mboom.core.frontend.BPU.component

import chisel3._
import mboom.config.CPUConfig.addrWidth

class RASEntry extends Bundle {
  val address = UInt(addrWidth.W)
  val count = UInt(8.W)
}

object RASEntry {
  def apply(address: UInt, count: UInt): RASEntry = {
    val e = Wire(new RASEntry)
    e.address := address
    e.count := count
    e
  }
}

// latency <= nWays (2)
// use XiangShan for reference
class RASRequest extends Bundle {
  val address = Input(UInt(addrWidth.W))
  val push_valid = Input(Bool())
  val pop_valid = Input(Bool())

  val entry = Output(new RASEntry)
  val sp = Output(UInt(5.W))

  val recover_valid = Input(Bool())
  val recover_push = Input(Bool())
  val recover_address = Input(UInt(addrWidth.W))
  val recover_pop = Input(Bool())

  val recover_entry = Input(new RASEntry)
  val recover_sp = Input(UInt(5.W))

  // val valid = Output(Bool())
}
@deprecated
class RAS(nWays: Int) extends Module {
  val io = IO(new RASRequest)

  val sp = RegInit(0.U(5.W))
  val ras_stack = RegInit(VecInit(for (i <- 0 until 32) yield {
    if (i == 0) RASEntry(0.U, 1.U) else RASEntry(0.U, 0.U)
  }))

  io.sp := sp;
  io.entry := ras_stack(sp)
  when(io.recover_valid) {
    update(1.B, io.recover_sp,
      io.recover_entry,
      io.recover_push,
      io.recover_address,
      io.recover_pop)
  }.elsewhen(io.push_valid || io.pop_valid) {
    update(0.B, sp, ras_stack(sp),
      io.push_valid, io.address, io.pop_valid)
  }

  def update(do_recover: Bool,
             do_sp: UInt,
             do_entry: RASEntry,
             do_push: Bool,
             do_address: UInt,
             do_pop: Bool) = {
    when(do_push) {
      when(do_entry.address === do_address) {
        ras_stack(do_sp) := RASEntry(do_address, do_entry.count + 1.U)
        sp := do_sp
      }.otherwise {
        when(do_recover) {
          ras_stack(do_sp) := do_entry
        }
        ras_stack(do_sp + 1.U) := RASEntry(do_address, 1.U)
        sp := do_sp + 1.U
      }
    }.elsewhen(do_pop) {
      when(do_entry.count >= 2.U) {
        ras_stack(do_sp) := RASEntry(do_entry.address, do_entry.count - 1.U)
        sp := do_sp
      }.otherwise {
        ras_stack(do_sp) := RASEntry(do_entry.address, 0.U)
        sp := do_sp - 1.U
      }
    }.otherwise {
      when(do_recover) {
        ras_stack(do_sp) := do_entry
        sp := do_sp
      }
    }
  }
}

class RAS1Request extends Bundle {
  // commit
  val address    = Input(UInt(addrWidth.W))
  val push_valid = Input(Bool())
  val pop_valid  = Input(Bool())

  val entry = Output(new RASEntry)
}

// without recover
class RAS1(nWays: Int) extends Module {
  val io = IO(new RAS1Request)

  val sp = RegInit(0.U(5.W))
  val ras_stack = RegInit(VecInit(for (i <- 0 until 32) yield {
    if (i == 0) RASEntry(0.U, 1.U) else RASEntry(0.U, 0.U)
  }))

  io.entry := ras_stack(sp)
  update(sp, ras_stack(sp), io.push_valid, io.address, io.pop_valid)

  def update(do_sp: UInt,
             do_entry: RASEntry,
             do_push: Bool,
             do_address: UInt,
             do_pop: Bool) = {
    when(do_push) {
      when(do_entry.address === do_address) {
        ras_stack(do_sp) := RASEntry(do_address, do_entry.count + 1.U)
        sp := do_sp
      }.otherwise {
        ras_stack(do_sp + 1.U) := RASEntry(do_address, 1.U)
        sp := do_sp + 1.U
      }
    }.elsewhen(do_pop) {
      when(do_entry.count >= 2.U) {
        ras_stack(do_sp) := RASEntry(do_entry.address, do_entry.count - 1.U)
        sp := do_sp
      }.otherwise {
        ras_stack(do_sp) := RASEntry(do_entry.address, 0.U)
        sp := do_sp - 1.U
      }
    }
  }
}