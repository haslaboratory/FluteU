package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.config.CPUConfig._
import mboom.config.CacheConfig

/**
  * AXIRead FSM who buffers both the req and resp
  *
  * @param axiId : AXI ID (4.W)
  * @param len   : 传输长度，单位：32bit
  */
class AXIBankRead(axiId: UInt, wrapped: Boolean = true)(implicit cacheConfig: CacheConfig)
    extends Module {
  private val len = (cacheConfig.numOfBanks - 1)

  val io = IO(new Bundle {
    val req  = Flipped(DecoupledIO(UInt(addrWidth.W)))
    val resp = ValidIO(Vec(cacheConfig.numOfBanks, UInt(dataWidth.W)))

    val axi = AXIIO.master()
  })

  val addrBuffer = RegInit(0.U(addrWidth.W))
  val dataBuffer = RegInit(VecInit(Seq.fill(cacheConfig.numOfBanks)(0.U(dataWidth.W))))
  val index      = RegInit(0.U(cacheConfig.bankIndexLen.W))

  val idle :: active :: transfer :: finish :: Nil = Enum(4)

  val state = RegInit(idle)

  // axi config
  io.axi.aw := DontCare
  io.axi.w  := DontCare
  io.axi.b  := DontCare

  val brust = if (wrapped) "b10".U(2.W) else "b01".U(2.W)

  io.axi.ar.bits.id    := axiId
  io.axi.ar.bits.addr  := Mux(state === active, addrBuffer, io.req.bits)
  io.axi.ar.bits.len   := len.U(4.W)
  io.axi.ar.bits.size  := "b010".U(3.W) // always 4 bytes
  io.axi.ar.bits.burst := brust         // axi wrap burst
  io.axi.ar.bits.lock  := 0.U
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot  := 0.U

  switch(state) {
    is(idle) {
      when(io.req.fire) {
        addrBuffer := io.req.bits
        index      := Mux(wrapped.B, cacheConfig.getBankIndex(io.req.bits), 0.U)
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
        dataBuffer(index) := io.axi.r.bits.data
        index             := index + 1.U

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
