package mboom.cache.components

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.config.CacheConfig

// no latency
class WriteBackQueue(capacity: Int = 8)(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
      val data = Vec(cacheConfig.numOfBanks, UInt(dataWidth.W))
    }))

    val deq = Decoupled(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
      val data = Vec(cacheConfig.numOfBanks, UInt(dataWidth.W))
    })

    val req = Flipped(Valid(new Bundle {
      val addr = UInt(dataWidth.W)
      val data = UInt(32.W)
      val writeMask = Vec(4, Bool())
    }))
    val resp = Valid(UInt(32.W))
    val empty = Output(Bool())
  })
  val head = RegInit(0.U(log2Ceil(capacity).W))
  val tail = RegInit(0.U(log2Ceil(capacity).W))
  val cnt  = RegInit(0.U(log2Ceil(capacity).W))

  val empty = cnt === 0.U
  val full  = cnt === capacity.U

  val addr  = Mem(capacity, UInt(cacheConfig.lineAddrLen.W))
  val data  = Mem(capacity, Vec(cacheConfig.numOfBanks, UInt(dataWidth.W)))
  val valid = RegInit(VecInit(Seq.fill(capacity)(0.B)))

  val hitRead = WireInit(VecInit(for (i <- 0 until capacity)
    yield valid(i) && addr(i) === cacheConfig.getLineAddr(io.req.bits.addr)))
  val hitWrite = WireInit(VecInit(for (i <- 0 until capacity)
    yield hitRead(i) && !(io.deq.fire && i.U === head)))

  val reqWrite     = io.req.bits.writeMask.reduce(_ || _)
  val hasReadHit   = hitRead.reduce(_ || _)
  val hasWriteHit  = hitWrite.reduce(_ || _)

  // enqueue
  io.enq.ready := !full
  when (io.enq.fire) {
    addr(tail)  := io.enq.bits.addr
    data(tail)  := io.enq.bits.data
    valid(tail) := 1.B
    tail        := tail + 1.U
  }
  // dequeue
  io.deq.valid     := !empty
  io.deq.bits.addr := addr(head)
  io.deq.bits.data := data(head)
  when (io.deq.fire) {
    valid(head)    := 0.B
    head           := head + 1.U
  }
  // cnt
  when (io.enq.fire && !io.deq.fire) {
    cnt := cnt + 1.U
  } .elsewhen (!io.enq.fire && io.deq.fire) {
    cnt := cnt - 1.U
  }
  // req + resp
  val bankIndex = cacheConfig.getBankIndex(io.req.bits.addr)

  var respData = WireInit(0.U(32.W))
  for (i <- 0 until capacity) {
    respData = respData | Mux(hitRead(i), data(i)(bankIndex), 0.U(32.W))
  }

  io.resp.valid := Mux(reqWrite, hasWriteHit, hasReadHit)
  io.resp.bits  := respData

  io.empty := empty

  for (j <- 0 until capacity) {
    when(reqWrite && hitWrite(j) && io.req.valid) {
      data(j)(bankIndex) := Cat(
        (3 to 0 by -1).map(i =>
          Mux(
            io.req.bits.writeMask(i),
            io.req.bits.data(7 + 8 * i, 8 * i),
            data(j)(bankIndex)(7 + 8 * i, 8 * i)
          )
        )
      )
    }
  }
}

// 简化设计，write back 需要等到写回全部完成再返回
// 故 query 不再查询 writeback queue 是否 hit
class WriteBackQueuev2(capacity: Int = 4)(implicit cacheConfig: CacheConfig) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
      val data = Vec(cacheConfig.numOfBanks, UInt(dataWidth.W))
    }))

    val deq = Decoupled(new Bundle {
      val addr = UInt(cacheConfig.lineAddrLen.W)
      val data = Vec(cacheConfig.numOfBanks, UInt(dataWidth.W))
    })

    val empty = Output(Bool())
  })

  val head = RegInit(0.U(log2Ceil(capacity).W))
  val tail = RegInit(0.U(log2Ceil(capacity).W))
  val cnt  = RegInit(0.U(log2Ceil(capacity+1).W))

  val empty = cnt === 0.U
  val full  = cnt === capacity.U

  val addr = Mem(capacity, UInt(cacheConfig.lineAddrLen.W))
  val data = Mem(capacity, Vec(cacheConfig.numOfBanks, UInt(dataWidth.W)))

  // enqueue
  io.enq.ready := !full
  when(io.enq.fire) {
    addr(tail) := io.enq.bits.addr
    data(tail) := io.enq.bits.data
    tail := tail + 1.U
  }
  // dequeue
  io.deq.valid := !empty
  io.deq.bits.addr := addr(head)
  io.deq.bits.data := data(head)
  when(io.deq.fire) {
    head := head + 1.U
  }
  // cnt
  when(io.enq.fire && !io.deq.fire) {
    cnt := cnt + 1.U
  }.elsewhen(!io.enq.fire && io.deq.fire) {
    cnt := cnt - 1.U
  }

  io.empty := empty
}
