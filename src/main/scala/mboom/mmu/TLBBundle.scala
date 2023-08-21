package mboom.mmu

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.cp0._

class PhysicalPage extends Bundle {
  val pfn   = UInt(20.W)
  val cache = UInt(3.W)
  val dirty = Bool()
  val valid = Bool()
}

class TLBEntry extends Bundle {
  val vpn    = UInt(19.W)
  val asid   = UInt(8.W)
  val global = Bool()
  val pages  = Vec(2, new PhysicalPage)
}

class SearchPort extends Bundle {
  val vpn     = Input(UInt(20.W))
  val pfn     = Output(UInt(20.W))
  val dirty   = Output(Bool())
  val uncache = Output(Bool())
  val valid   = Output(Bool())
}

class WritePort extends Bundle {
  val fromIndex  = Input(Bool())
  val fromRandom = Input(Bool())
}

class ReadPort extends Bundle {
  val read = Output(new TLBEntry())  // to entryhi entrylo0 entrylo1
}

class ProbePort extends Bundle{
  val probe = Output(UInt(32.W))  // to index
}

class TLBReq extends Bundle {
  val write = new WritePort
  val read  = new ReadPort
  val probe = new ProbePort
}

class TLBRegs extends Bundle {
  val pageMask = new PageMaskBundle
  val entryHi  = new EntryHiBundle
  val entryLo0 = new EntryLoBundle
  val entryLo1 = new EntryLoBundle
  val index    = new IndexBundle
  val random   = new RandomBundle
  val kseg0Uncached = Bool()
}

class TLBInstrEx extends Bundle {
  val refill  = Bool()
  val invalid = Bool()
}

class TLBDataEx extends Bundle {
  val refill   = Bool()
  val invalid  = Bool()
  val modified = Bool()
}

class TLBInstrResp extends Bundle {
  val valid   = Output(Bool())
  val pfn     = Output(UInt(20.W))
  val uncache = Output(Bool())
  val except  = Output(new TLBInstrEx)
}

class TLBDataResp extends Bundle {
  val pfn     = Output(UInt(20.W))
  val uncache = Output(Bool())
  val except  = Output(new TLBDataEx)
}
class TLBWithCP0 extends Bundle {
  val regs  = Input(new TLBRegs)
}
