package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.config.CPUConfig._
import mboom.config.CacheConfig

// design for LW
class AXIRead(axiId: UInt) extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(DecoupledIO(UInt(addrWidth.W))) // addr按字节编址
    val resp = ValidIO(UInt(dataWidth.W))

    val axi = AXIIO.master()
  })

  val addrBuffer = RegInit(0.U(addrWidth.W))
  val dataBuffer = RegInit(0.U(dataWidth.W))

  val idle :: active :: transfer :: finish :: Nil = Enum(4)

  val state = RegInit(idle)

  // axi config
  io.axi.aw := DontCare
  io.axi.w  := DontCare
  io.axi.b  := DontCare

  io.axi.ar.bits.id    := axiId
  io.axi.ar.bits.addr  := addrBuffer
  io.axi.ar.bits.len   := 0.U(4.W)      /// 只传输1拍
  io.axi.ar.bits.size  := MuxCase("b010".U(3.W), Seq(
    (addrBuffer(0) === 1.U(1.W)) -> "b000".U(3.W),
    (addrBuffer(1) === 1.U(1.W)) -> "b001".U(3.W)
  ))
  io.axi.ar.bits.burst := "b10".U(2.W)  // axi wrap burst
  io.axi.ar.bits.lock  := 0.U
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot  := 0.U

  switch(state) {
    is(idle) {
      when(io.req.fire) {
        addrBuffer := io.req.bits
        state      := active
      }
    }
    is(active) {
      when(io.axi.ar.fire) {
        state := transfer
      }
    }
    is(transfer) {
      when(io.axi.r.fire && io.axi.r.bits.id === axiId) {
        dataBuffer := io.axi.r.bits.data
        when(io.axi.r.bits.last) {
          state := finish
        }
      }
    }
    is(finish) {
      state := idle
    }
  }

  io.axi.ar.valid := (state === active)
  io.axi.r.ready  := (state === transfer)

  io.req.ready  := (state === idle)
  io.resp.valid := (state === finish)

  io.resp.bits := dataBuffer

}
