package mboom.cp0

import chisel3._

class ExceptionBundle extends Bundle {
  val adELi = Bool() // addr error load Instruction
  val adELd = Bool() // addr error load Data
  val adES  = Bool() // addr error store
  val sys   = Bool() // syscall
  val bp    = Bool() // breakpoint
  val ri    = Bool() // reserved instruction
  val ov    = Bool() // overflow
  // TODO：添加exception支持TLB  TLB Refill / TLB Invalid / TLB Modified
  val tlblRfl = Bool()
  val tlblInv = Bool()
  val tlbsRfl = Bool()
  val tlbsInv = Bool()
  val tlbsMod = Bool()

  val trap  = Bool()
}

object ExceptionCode {
  val amount = 11
  val width  = 5 // Priviledged Resource Architecture demands this

  val int  = 0x00.U(width.W)
  val adEL = 0x04.U(width.W)
  val adEs = 0x05.U(width.W)
  val sys  = 0x08.U(width.W)
  val bp   = 0x09.U(width.W)
  val ri   = 0x0a.U(width.W)
  val ov   = 0x0c.U(width.W)

  // tlb
  val tlbl = 0x02.U(width.W)
  val tlbs = 0x03.U(width.W)
  val mod  = 0x01.U(width.W)

  // trap
  val trap = 0x0d.U(width.W)
}
