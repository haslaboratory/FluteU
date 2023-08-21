package mboom.cache.axi

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.config.CacheConfig

/** TODO
  * modified from Amadeus-Mips, needs to be fixed a litte
  *
  * @param addrReqWidth
  * @param AXIID
  * @param cacheConfig
  */
class AXIReadPort(addrReqWidth: Int = 32, AXIID: UInt, unCacheReadSize: Int = 3)(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {

    /** standard axi interface */
    val axi = AXIIO.master()

    /** when request request is valid, try to start a read transaction.
      * a read transaction is started successfully when there is a successful handshake*/
    /** This Unit DOES ~NOT~ buffer addr. 
      * So the addrReq.bits allow may not consist the same before finishTransfer is high */
    val addrReq = Flipped(Valid(UInt(addrReqWidth.W)))
    val uncache = Input(Bool())

    /** when transfer data is valid, the data carried is valid in this cycle */
    val transferData = Valid(UInt(32.W))

    /** indicate when a read transaction finishes (rlast carry through) */
    val lastBeat = Output(Bool())
  })

  require(addrReqWidth <= 32, "request should be less than 32 bits wide")

  val readIdle :: readWaitForAR :: readTransfer :: Nil = Enum(3)
  val readState                                        = RegInit(readIdle)
  val addrBuffer = RegInit(0.U(addrReqWidth.W))

  io.axi.aw := DontCare
  io.axi.w  := DontCare
  io.axi.b  := DontCare
  // axi signals
  io.axi.ar.bits.id    := AXIID
  io.axi.ar.bits.addr  := addrBuffer
  io.axi.ar.bits.len   := (cacheConfig.numOfBanks - 1).U(4.W)
  
  io.axi.ar.bits.size  := "b010".U(3.W) // always 4 bytes
  io.axi.ar.bits.burst := "b10".U(2.W) // axi wrap burst

  io.axi.ar.bits.lock  := 0.U
  io.axi.ar.bits.cache := 0.U
  io.axi.ar.bits.prot  := 0.U

  switch(readState) {
    is(readIdle) {
      when(io.addrReq.valid) {
        readState := readWaitForAR
        addrBuffer := io.addrReq.bits
      }
    }
    is(readWaitForAR) {
      when(io.axi.ar.fire) {
        readState := readTransfer
      }
    }
    is(readTransfer) {
      when(io.axi.r.fire && io.axi.r.bits.last) {
        readState := readIdle
      }
    }
    
  }

  // valid and ready signals
  io.axi.ar.valid := readState === readWaitForAR
  io.axi.r.ready  := readState === readTransfer

  io.transferData.valid := readState === readTransfer && io.axi.r.fire
  io.transferData.bits  := io.axi.r.bits.data

  io.lastBeat := readState === readTransfer && io.axi.r.fire && io.axi.r.bits.last
}
