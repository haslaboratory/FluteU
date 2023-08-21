package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.components.SinglePortQueue
import mboom.config.CPUConfig._

class AXIOutstandingWrite(axiId: UInt) extends Module {

  private val capacity = 8

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val addr = UInt(addrWidth.W)
      val valid = Vec(4, Bool())
      val data = UInt(dataWidth.W)

    }))

    val writeNum = Output(UInt(log2Ceil(2 * capacity).W))
    val release   = Output(Bool())

    val axi = AXIIO.master()
  })

  val dataQueue = Module(new SinglePortQueue(UInt(32.W), capacity, false))
  val addrQueue = Module(new SinglePortQueue(UInt(32.W), capacity, false))
  val maskQueue = Module(new SinglePortQueue(UInt(4.W) , capacity, false))

  val waitQueue = Module(new SinglePortQueue(UInt(32.W), capacity, false))

  val writeNum = WireDefault(0.U(4.W))
  writeNum     := addrQueue.io.count + waitQueue.io.count

  io.writeNum := writeNum
  io.release  := (addrQueue.io.count === 0.U) && (waitQueue.io.count === 0.U)

  io.req.ready := writeNum < capacity.U

  addrQueue.io.enq.valid := io.req.fire
  addrQueue.io.enq.bits  := io.req.bits.addr
  dataQueue.io.enq.valid := io.req.fire
  dataQueue.io.enq.bits  := io.req.bits.data
  maskQueue.io.enq.valid := io.req.fire
  maskQueue.io.enq.bits  := Cat(io.req.bits.valid(3), io.req.bits.valid(2), io.req.bits.valid(1), io.req.bits.valid(0))

  addrQueue.io.deq.ready := io.axi.w.fire
  dataQueue.io.deq.ready := io.axi.w.fire
  maskQueue.io.deq.ready := io.axi.w.fire

  waitQueue.io.enq.valid := io.axi.w.fire
  waitQueue.io.enq.bits  := addrQueue.io.deq.bits

  waitQueue.io.deq.ready := io.axi.b.fire

  val control :: write :: Nil = Enum(2)
  val state = RegInit(control)

  switch (state) {
    is (control) {
      when (io.axi.aw.fire) {
        state := write
      }
    }
    is (write) {
      when (io.axi.w.fire) {
        state := control
      }
    }
  }
  // axi config
  io.axi.ar := DontCare
  io.axi.r  := DontCare

  io.axi.aw.valid      := addrQueue.io.deq.valid && (state === control)
  io.axi.aw.bits.addr  := addrQueue.io.deq.bits
  io.axi.aw.bits.id    := axiId
  io.axi.aw.bits.burst := "b01".U(2.W)
  io.axi.aw.bits.len   := 0.U(4.W) /// 只传输1拍
  io.axi.aw.bits.size  := "b010".U(3.W)
  io.axi.aw.bits.cache := 0.U
  io.axi.aw.bits.prot  := 0.U
  io.axi.aw.bits.lock  := 0.U

  io.axi.w.valid       := dataQueue.io.deq.valid && (state === write)
  io.axi.w.bits.data   := dataQueue.io.deq.bits
  io.axi.w.bits.id     := axiId
  io.axi.w.bits.last   := 1.B
  io.axi.w.bits.strb   := maskQueue.io.deq.bits

  io.axi.b.ready       := 1.B
}
