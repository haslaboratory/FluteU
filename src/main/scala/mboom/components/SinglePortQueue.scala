package mboom.components

import chisel3._
import chisel3.util._

// with init value 0
class SinglePortQueue[T <: Data](val gen: T, val entries: Int, val hasFlush: Boolean) extends Module {
  val io = IO(new QueueIO(gen, entries, hasFlush))

  val ram        = RegInit(0.U.asTypeOf(Vec(entries, gen)))
  val enq_ptr    = Counter(entries)
  val deq_ptr    = Counter(entries)
  val maybe_full = RegInit(false.B)
  val ptr_match  = enq_ptr.value === deq_ptr.value
  val empty      = ptr_match && !maybe_full
  val full       = ptr_match && maybe_full
  val do_enq     = WireDefault(io.enq.fire)
  val do_deq     = WireDefault(io.deq.fire)
  val flush      = io.flush.getOrElse(false.B)

  when(do_enq) {
    ram(enq_ptr.value) := io.enq.bits
    enq_ptr.inc()
  }
  when(do_deq) {
    deq_ptr.inc()
  }
  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }
  when(flush) {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
  }

  io.deq.valid := !empty
  io.enq.ready := !full

  io.deq.bits := ram(deq_ptr.value)

  val ptr_diff = enq_ptr.value - deq_ptr.value

  if (isPow2(entries)) {
    io.count := Mux(maybe_full && ptr_match, entries.U, 0.U) | ptr_diff
  } else {
    io.count := Mux(
      ptr_match,
      Mux(maybe_full, entries.asUInt, 0.U),
      Mux(deq_ptr.value > enq_ptr.value, entries.asUInt + ptr_diff, ptr_diff)
    )
  }
}
