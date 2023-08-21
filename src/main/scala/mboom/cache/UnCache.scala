package mboom.cache


import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.cache.axi.{AXIOutstandingWrite, AXIRead, AXIWriteWithConvertedData}
import mboom.core.backend.decode.StoreMode
import mboom.core.backend.lsu.MemReq
import mboom.util.AddrMap

class UnCache extends Module {
  val io = IO(new Bundle {
    val core = new DCacheWithCore
    val axi = AXIIO.master()
    val release = Output(Bool())
  })

  val axiRead = Module(new AXIRead(axiId = 2.U))
  val axiWrite = Module(new AXIOutstandingWrite(axiId = 2.U))

  val idle :: write :: read :: Nil = Enum(3)
  val state = RegInit(idle)

  val isRead  = !io.core.req.bits.validMask.reduce(_ || _)
  val isWrite = io.core.req.bits.validMask.reduce(_ || _)

  val readEnable  = (state === idle)
  val writeEnable = (state =/= read) && axiWrite.io.req.ready

  val pipeFlush = RegInit(0.B)

  when (state === read && io.core.flush && !axiRead.io.resp.valid) {
    pipeFlush := 1.B
  }

  switch(state) {
    is(idle) {
      when (io.core.req.fire && !io.core.flush && isRead) {
        state := read
      }

      when (io.core.req.fire && isWrite) {
        state := write
      }
    }

    is (read) {
      when (axiRead.io.resp.valid) {
        state     := idle
        pipeFlush := 0.B
      }
    }

    is (write) {
      when (axiWrite.io.writeNum === 0.U) {
        state := idle
      }
    }
  }

  axiRead.io.req.bits  := io.core.req.bits.addr
  axiRead.io.req.valid := io.core.req.fire && isRead && !io.core.flush

  axiWrite.io.req.bits.addr  := io.core.req.bits.addr
  axiWrite.io.req.bits.data  := io.core.req.bits.writeData
  axiWrite.io.req.bits.valid := io.core.req.bits.validMask
  axiWrite.io.req.valid := io.core.req.fire && isWrite

  io.core.req.ready := (isRead && readEnable) || (isWrite && writeEnable)

  // for test

  io.core.resp.bits.loadData := axiRead.io.resp.bits
  io.core.resp.valid := axiRead.io.resp.valid && state === read && !io.core.flush && !pipeFlush

  io.core.inform := 0.U.asTypeOf(Valid(new InformMeta))

  io.axi.ar <> axiRead.io.axi.ar
  io.axi.r <> axiRead.io.axi.r
  io.axi.aw <> axiWrite.io.axi.aw
  io.axi.w <> axiWrite.io.axi.w
  io.axi.b <> axiWrite.io.axi.b

  axiRead.io.axi.aw := DontCare
  axiRead.io.axi.w := DontCare
  axiRead.io.axi.b := DontCare
  axiWrite.io.axi.ar := DontCare
  axiWrite.io.axi.r := DontCare

  io.release := axiWrite.io.release

}

class UnCachev2 extends Module {
  val io = IO(new Bundle {
    val core = new DCacheWithCorev2
    val axi = AXIIO.master()
    val release = Output(Bool())
  })

  val axiRead = Module(new AXIRead(axiId = 2.U))
  val axiWrite = Module(new AXIOutstandingWrite(axiId = 2.U))

  val idle :: write :: read :: Nil = Enum(3)
  val state = RegInit(idle)

  val isRead  = !io.core.req.bits.validMask.reduce(_ || _)
  val isWrite = io.core.req.bits.validMask.reduce(_ || _)

  val readEnable  = (state === idle)
  val writeEnable = (state =/= read) && axiWrite.io.req.ready

  val pipeFlush = RegInit(0.B)

  val meta = RegInit(0.U.asTypeOf(new MemReq))

  when (state === read && DataCacheUtils.needFlush(io.core.flush, meta) && !axiRead.io.resp.valid) {
    pipeFlush := 1.B
  }

  switch(state) {
    is(idle) {
      when (io.core.req.fire && isRead) {
        state := read
        meta  := io.core.req.bits.meta
      }

      when (io.core.req.fire && isWrite) {
        state := write
      }
    }

    is (read) {
      when (axiRead.io.resp.valid) {
        state     := idle
        pipeFlush := 0.B
      }
    }

    is (write) {
      when (axiWrite.io.writeNum === 0.U) {
        state := idle
      }
    }
  }

  axiRead.io.req.bits  := io.core.req.bits.addr
  axiRead.io.req.valid := io.core.req.fire && isRead

  axiWrite.io.req.bits.addr  := io.core.req.bits.addr
  axiWrite.io.req.bits.data  := io.core.req.bits.writeData
  axiWrite.io.req.bits.valid := io.core.req.bits.validMask
  axiWrite.io.req.valid := io.core.req.fire && isWrite

  io.core.req.ready := (isRead && readEnable) || (isWrite && writeEnable)

  // for test

  io.core.resp.bits.loadData := axiRead.io.resp.bits
  io.core.resp.bits.meta     := meta
  io.core.resp.valid         :=
    axiRead.io.resp.valid && state === read &&
      !DataCacheUtils.needFlush(io.core.flush, meta) && !pipeFlush

  io.core.inform := 0.U.asTypeOf(Valid(new InformMeta))

  io.axi.ar <> axiRead.io.axi.ar
  io.axi.r <> axiRead.io.axi.r
  io.axi.aw <> axiWrite.io.axi.aw
  io.axi.w <> axiWrite.io.axi.w
  io.axi.b <> axiWrite.io.axi.b

  axiRead.io.axi.aw := DontCare
  axiRead.io.axi.w := DontCare
  axiRead.io.axi.b := DontCare
  axiWrite.io.axi.ar := DontCare
  axiWrite.io.axi.r := DontCare

  io.release := axiWrite.io.release

}