package mboom.cache

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import mboom.axi.AXIIO
import mboom.cache.axi.{AXIBankRead, AXIBankWrite, AXIOutstandingWrite, AXIRead, AXIWrite}
import mboom.config.CPUConfig._
import mboom.config.CacheConfig
import mboom.core.backend.decode.StoreMode
import mboom.util.AddrMap
import mboom.cache.components.TagValidBundle
import mboom.cache.lru.{LRU, PLRU}
import mboom.components.{TypedDualPortAsyncRam, TypedDualPortRam, TypedSinglePortRam, TypedDualPortByteWriteRam}
import mboom.cache.components.WriteBackQueue
import mboom.cache.components.WriteBackQueuev2
import mboom.core.backend.ExecFlush
import mboom.core.backend.lsu.MemReq
import mboom.mmu.TLBDataResp

/**
 *
 * [[storeMode]] 为 [[disable]] 时，为 Load 指令。
 */
class DCacheReq extends Bundle {
  val addr      = UInt(addrWidth.W)
  val storeMode = UInt(StoreMode.width.W)
  val writeData = UInt(dataWidth.W)
}

class InformMeta extends Bundle {
  val writeEnable  = Bool()
  val writeRegAddr = UInt(phyRegAddrWidth.W)
}
class DCacheReq1 extends Bundle {
  val addr      = UInt(addrWidth.W)

  val validMask = Vec(4, Bool())
  val writeData = UInt(dataWidth.W)
  val meta      = new InformMeta
}

class DCacheResp extends Bundle {
  val loadData = UInt(dataWidth.W)
}


class CacheInvalidate extends Bundle {
  // index 被补齐成32位便于cache第二周期对齐
  val req  = Flipped(Decoupled(UInt(32.W)))
  val resp = Output(Bool())
}

class DCacheWithCore extends Bundle {
  val req = Flipped(DecoupledIO(new DCacheReq1))

  /** valid 标志一次有效的load数据, store类型的请求不做任何回应 */
  val resp = ValidIO(new DCacheResp)

  val inform = ValidIO(new InformMeta)

  val flush = Input(Bool())
}

object DataCacheUtils {
  def generateWriteMask(storeMode: UInt, addr: UInt): Vec[Bool] = {
    val writeMask = WireInit(0.U.asTypeOf(Vec(4, Bool())))
    when(storeMode === StoreMode.disable) {
      writeMask := 0.U.asTypeOf(Vec(4, Bool()))
    }.elsewhen(storeMode === StoreMode.word) {
      writeMask := VecInit(Seq.fill(4)(1.B))
    }.elsewhen(storeMode === StoreMode.halfword) {
      writeMask := VecInit(addr(1), addr(1), !addr(1), !addr(1))
    }.otherwise {
      writeMask := UIntToOH(addr(1, 0))
    }

    writeMask
  }

  def generateWriteBack(data1: UInt, data2: UInt, mask: Vec[Bool]): UInt = {
    assert(dataWidth == 32)
    val data = Seq.fill(4)(WireInit(0.U(8.W)))
    for (i <- 0 until 4) {
      data(i) := Mux(mask(i), data2(8 * i + 7, 8 * i), data1(8 * i + 7, 8 * i))
    }
    Cat(data(3), data(2), data(1), data(0))
  }

  def needFlush(flush: ExecFlush, req: MemReq): Bool = {
    flush.extFlush || (flush.brMissPred && (flush.brMask & req.brMask).orR)
  }
}

class DataCache(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays = config.numOfWays
  val nSets = config.numOfSets
  val nBanks = config.numOfBanks
  val io = IO(new Bundle {
    val core = new DCacheWithCore
    val axi = AXIIO.master()
    val inv = new CacheInvalidate
    val release = Output(Bool())

    val debug_s2_valid = Output(Bool())
    val debug_s2_addr  = Output(UInt(32.W))
    val debug_s2_state = Output(UInt(4.W))
  })
  // Stage0 -> a; Stage2 -> b;
  val tagBanks = for (i <- 0 until nWays)
    yield Module(new TypedDualPortAsyncRam(nSets, new TagValidBundle))

  // Stage1 -> a; Stage2 -> b;
  val dirtyBanks = for (i <- 0 until nWays)
    yield Module(new TypedSinglePortRam(nSets, Bool()))
  val dataBanks = for (i <- 0 until nWays)
    yield for (j <- 0 until nBanks)
      yield Module(new TypedDualPortRam(nSets, UInt(dataWidth.W)))

  // Write Back Queue
  val writeQueue = Module(new WriteBackQueue(capacity = 4))

  // LRU
  val lruUnit = Module(new PLRU(nSets, nWays))

  // AXI Read / Write
  val axiBankRead = Module(new AXIBankRead(axiId = 1.U))
  val axiBankWrite = Module(new AXIBankWrite(axiId = 1.U))

  // ---------------- state definitions -------------------------------
  val idle :: refilling :: waiting :: receiving :: writing :: invalidating :: invalidated :: Nil = Enum(7)
  val state = RegInit(idle)
  val nxtState = WireInit(state)
  val wb_idle :: wb_writing :: Nil = Enum(2)
  val wbState = RegInit(wb_idle)

  // stage 0 (access tagBanks && WriteBackQueue)
  val s1Stall = WireInit(0.B)

  val s1Valid = RegInit(0.B)
  val s1Addr = RegInit(0.U(addrWidth.W))
  val s1Meta = RegInit(0.U.asTypeOf(new InformMeta))
  val s1Write = RegInit(0.B)
  val s1WriteMask = RegInit(0.U.asTypeOf(Vec(4, Bool())))
  val s1WriteData = RegInit(0.U(dataWidth.W))
  val s1HitTag = RegInit(0.B)
  val s1HitWay = RegInit(0.U(log2Ceil(nWays).W))
  val s1Data = WireInit(0.U(dataWidth.W)) // important
  val s1HitFIFO = RegInit(0.B)
  val s1FIFOData = RegInit(0.U(dataWidth.W))

  // flush read signals
  val reqWrite = io.core.req.bits.validMask.reduce(_ || _)
  val reqFlush = io.core.req.valid && !reqWrite && io.core.flush
  val s1Flush = s1Valid && !s1Write && io.core.flush

  // access tagBanks in the first cycle (no latency)
  val hitTag = WireInit(0.U.asTypeOf(Vec(nWays, Bool())))
  for (i <- 0 until nWays) {
    tagBanks(i).io.wea := 0.B
    tagBanks(i).io.dina := 0.U.asTypeOf(new TagValidBundle)
    tagBanks(i).io.addra := cacheConfig.getIndex(io.core.req.bits.addr)
    hitTag(i) := tagBanks(i).io.douta.valid &&
      tagBanks(i).io.douta.tag === cacheConfig.getTag(io.core.req.bits.addr)
  }

  // access datBanks first
  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.wea := 0.B
      dataBanks(i)(j).io.dina := 0.U
      dataBanks(i)(j).io.addra := Mux(s1Stall, cacheConfig.getIndex(s1Addr),
        cacheConfig.getIndex(io.core.req.bits.addr))
    }
  }

  // access FIFO in the first cycle (no latency)
  writeQueue.io.req.valid := io.core.req.fire
  writeQueue.io.req.bits.addr := io.core.req.bits.addr
  writeQueue.io.req.bits.data := io.core.req.bits.writeData
  writeQueue.io.req.bits.writeMask := io.core.req.bits.validMask

  // s1HitTag && s1HitWay will be assigned below
  when(!s1Stall) {
    // no flush in write
    s1Valid := io.core.req.fire && !reqFlush
    s1Addr := io.core.req.bits.addr
    s1Meta := io.core.req.bits.meta
    s1Write := reqWrite
    s1WriteMask := io.core.req.bits.validMask
    s1WriteData := io.core.req.bits.writeData
    s1HitFIFO := writeQueue.io.resp.valid
    s1FIFOData := writeQueue.io.resp.bits
  }

  val s1BankIdx = cacheConfig.getBankIndex(s1Addr)
  val s1DataOut = VecInit(for (i <- 0 until nWays)
    yield VecInit(for (j <- 0 until nBanks)
      yield dataBanks(i)(j).io.douta))
  s1Data := s1DataOut(s1HitWay)(s1BankIdx)
  //  s1HitTag := hitTag.reduce(_ || _)
  //  s1HitWay := OHToUInt(hitTag)

  val s1WriteBack = DataCacheUtils.generateWriteBack(s1Data, s1WriteData, s1WriteMask)

  // stage 1 (process dataBanks)
  val s2Stall = WireInit(0.B)

  val s2Valid      = RegInit(0.B)
  val s2Inv        = RegInit(0.B)
  val s2Addr       = RegInit(0.U(addrWidth.W))
  val s2Meta       = RegInit(0.U.asTypeOf(new InformMeta))
  val s2Write      = RegInit(0.B)
  val s2WriteData  = RegInit(0.U(dataWidth.W))
  val s2WriteMask  = RegInit(0.U.asTypeOf(Vec(4, Bool())))
  val s2HitInCache = RegInit(0.B)
  val s2HitWay     = RegInit(0.U)
  val s2Data       = RegInit(0.U(dataWidth.W))
  val s2WBValid    = RegInit(0.B)
  val s2WriteBack  = RegInit(0.U(dataWidth.W))

  val s2HitInRefill = RegInit(0.B)

  val s2Index = cacheConfig.getIndex(s2Addr)
  val s2BankIdx = cacheConfig.getBankIndex(s2Addr)

  // adjacant write
  val s1AdjacantWriteBack = DataCacheUtils.generateWriteBack(s2WriteBack, s1WriteData, s1WriteMask)


  lruUnit.io.getLRU.index := s2Index
  lruUnit.io.getLRU.valid := 1.B
  val s2LRUWay = lruUnit.io.getLRU.way

  lruUnit.io.update.valid := (s2HitInCache && s2Valid) || (state === writing && s2Valid)
  lruUnit.io.update.bits.way := Mux(state === idle, s2HitWay, s2LRUWay)
  lruUnit.io.update.bits.index := cacheConfig.getIndex(s2Addr)

  val s2Miss = s2Valid && !s2HitInCache && !s2HitInRefill
  val s2InvComplete = RegInit(0.B)

  // flush read signals
  val s2Flush = s2Valid && !s2Inv && !s2Write && io.core.flush
  val pipeFlush = RegInit(0.B)
  val flushOutput = pipeFlush || s2Flush

  // Stall
  s1Stall := s2Stall && !s1Flush
  s2Stall := (state =/= idle) || (s2Valid && !s2Inv && s2Miss) || (s2Inv && !s2InvComplete)

  val s2DirtyOut = VecInit(for (i <- 0 until nWays) yield dirtyBanks(i).io.dataOut)
  val s2DataOut  =
    VecInit(for (i <- 0 until nWays) yield
      VecInit(for (j <- 0 until nBanks) yield
        dataBanks(i)(j).io.doutb))
  val s2TagOut = VecInit(for (i <- 0 until nWays) yield tagBanks(i).io.doutb.tag)

  val s2Dirty     = s2DirtyOut(s2LRUWay)
  val s2Victim    = s2DataOut(s2LRUWay)
  val s2VictimTag = s2TagOut(s2LRUWay)

  /* Process adjacant problem (s2 write && s1 read the same place)
     We can process cache miss in the same way, but it is complex.
    */
  // read / write after write
  val s1s2DataAdjacent =
  s1Valid && s2Valid && s2WBValid && (s2Addr === s1Addr)

  val s1s2HitAdjacantIn = (state === writing) &&
    cacheConfig.getLineAddr(s1Addr) === cacheConfig.getLineAddr(s2Addr)

  val s1s2HitAdjacantOut = (state === writing) && s1HitTag && s1HitWay === s2LRUWay &&
    cacheConfig.getIndex(s1Addr) === cacheConfig.getIndex(s2Addr)

  when(s1s2HitAdjacantIn) {
    s1HitTag := 1.B
    s1HitWay := s2LRUWay
  }.elsewhen(s1s2HitAdjacantOut) {
    s1HitTag := 0.B
  }.elsewhen(!s1Stall) {
    s1HitTag := hitTag.reduce(_ || _)
    s1HitWay := OHToUInt(hitTag)
  }

  /////////////////////////////////////////////////////////////////////

  io.inv.req.ready := !s2Valid && !s1Valid
  val reqInv = io.inv.req.fire

  io.inv.resp := s2InvComplete

  when(!s2Stall) {
    s2Valid      := (s1Valid && !s1Flush) || reqInv
    s2Inv        := reqInv
    s2Addr       := Mux(reqInv, io.inv.req.bits, s1Addr)
    s2Meta       := s1Meta
    s2Write      := s1Write
    s2WriteData  := s1WriteData
    s2WriteMask  := s1WriteMask
    s2HitInCache := (s1HitTag || s1HitFIFO)
    s2HitWay     := s1HitWay
    s2Data       := MuxCase(s1Data, Seq(
      s1s2DataAdjacent -> s2WriteBack,
      s1HitFIFO -> s1FIFOData
    ))
    s2WBValid    := s1HitTag && s1Write && s1Valid
    s2WriteBack  := Mux(s1s2DataAdjacent, s1AdjacantWriteBack, s1WriteBack)

  }

  // refill control
  when(!s2Stall) {
    s2HitInRefill := 0.B
  }.elsewhen(nxtState === writing) {
    s2HitInRefill := 1.B
  }

  // invalidate control
  when (!s2Stall) {
    s2InvComplete := 0.B
  } .elsewhen(nxtState === idle && state === invalidated) {
    s2InvComplete := 1.B
  }

  // ---------------- refilling writeQueue push (writeback) -------------------
  val axiWriteToRead = WireInit(0.B)

  val writeQueueEnqReady = writeQueue.io.enq.ready
  val writeQueueEnqBlocked = s2Dirty && !writeQueueEnqReady

  // victim
  val victimEnqValid = (state === refilling) && s2Dirty
  val victimEnqAddr  = Cat(s2VictimTag, s2Index)
  val victimEnqData  = s2Victim

  // invalidate
  val invWay = RegInit(0.U(log2Up(cacheConfig.numOfWays).W))

  val invEnqValid = (state === invalidating) && s2DirtyOut(invWay)
  val invEnqAddr  = Cat(s2TagOut(invWay), s2Index)
  val invEnqData  = s2DataOut(invWay)

  writeQueue.io.enq.valid     := invEnqValid || victimEnqValid
  writeQueue.io.enq.bits.addr := Mux(state === invalidating, invEnqAddr, victimEnqAddr)
  writeQueue.io.enq.bits.data := Mux(state === invalidating, invEnqData, victimEnqData)

  // ---------------- axiBankRead req ----------------------------------
  val axiReadReady = axiBankRead.io.req.ready
  val axiReadAddr = Cat(cacheConfig.getLineAddr(s2Addr), 0.U((32 - cacheConfig.lineAddrLen).W))

  axiBankRead.io.req.valid := (state === waiting)
  axiBankRead.io.req.bits := axiReadAddr

  // ----------------- writeQueue pop && axiBankWrite req ---------------------------
  val writeQueueDeqValid = writeQueue.io.deq.valid
  val axiWriteReady = axiBankWrite.io.req.ready

  val axiWriteAddr = RegInit(0.U(addrWidth.W))
  val axiWriteLine = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))

  writeQueue.io.deq.ready := (wbState === wb_idle) && axiWriteReady

  axiBankWrite.io.req.valid := (wbState === wb_idle) && writeQueueDeqValid
  axiBankWrite.io.req.bits.data := writeQueue.io.deq.bits.data
  axiBankWrite.io.req.bits.addr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))

  // ----------------- axi Read to Cache -------------------------------
  val axiWriteToCache = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))
  val axiReadLine = WireInit(axiBankRead.io.resp.bits)
  when(axiWriteToRead) {
    axiReadLine := axiWriteLine
  }

  val axiNewCacheLine = WireInit(axiReadLine)
  when(s2Write) {
    axiNewCacheLine(s2BankIdx) := DataCacheUtils.generateWriteBack(axiReadLine(s2BankIdx), s2WriteData, s2WriteMask)
  }

  // 注意！ refilling 状态机和 writeback 状态机可能有严重的竞争问题！
  // 当数据正在写回的同时请求同一个数据会得到错误的老数据 ！！！
  axiWriteToRead := wbState =/= wb_idle && axiWriteAddr === axiReadAddr && (writeQueueEnqReady || !s2Dirty)
  switch(state) {
    is(idle) {
      // 若 s2Flush 则不进入refilling阶段
      when(!s2Inv && s2Miss) {
        nxtState := refilling
      } .elsewhen(s2Inv && !s2InvComplete) {
        invWay   := (cacheConfig.numOfWays-1).U
        nxtState := invalidating
      }
    }
    is(refilling) {
      when (!s2Dirty || writeQueueEnqReady) {
        when (axiWriteToRead) {
          nxtState        := writing
          axiWriteToCache := axiNewCacheLine
        } .otherwise {
          nxtState        := waiting
        }
      }
    }
    is(waiting) {
      when(axiBankRead.io.req.fire) {
        nxtState := receiving
      }
    }
    is(receiving) {
      when(axiBankRead.io.resp.fire) {
        nxtState := writing
        axiWriteToCache := axiNewCacheLine
      }
    }
    is(writing) {
      nxtState := idle
    }
    is(invalidating) {
      when (writeQueueEnqReady) {
        when (invWay === 0.U) {
          nxtState      := invalidated
        } .otherwise {
          invWay        := invWay - 1.U
        }
      }
    }
    is(invalidated) {
      when (writeQueue.io.empty && wbState === idle) {
        nxtState := idle
      }
    }
  }
  state := nxtState

  switch(wbState) {
    is(wb_idle) {
      when(axiBankWrite.io.req.fire) {
        wbState := wb_writing
        axiWriteAddr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))
        axiWriteLine := writeQueue.io.deq.bits.data
      }
    }
    is(wb_writing) {
      when(axiBankWrite.io.resp) {
        wbState := wb_idle
      }
    }
  }

  // process write
  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.web :=
        (state === writing && i.U === s2LRUWay) ||
        (s2Valid && s2WBValid && i.U === s2HitWay && j.U === s2BankIdx)

      dataBanks(i)(j).io.dinb := Mux(
        state === writing,
        axiWriteToCache(j),
        s2WriteBack
      )
      dataBanks(i)(j).io.addrb := s2Index
    }

    dirtyBanks(i).io.write :=
        (state === writing && i.U === s2LRUWay) ||
        (state === invalidated) ||
        (s2Valid && s2WBValid && i.U === s2HitWay)

    dirtyBanks(i).io.dataIn := MuxCase(0.B, Seq(
      (state === writing)     -> s2Write,
      (state === invalidated) -> 0.B,
      (state === idle)        -> 1.B
    ))
    dirtyBanks(i).io.addr := s2Index

    tagBanks(i).io.web   :=
      (state === writing && i.U === s2LRUWay) || (state === invalidated)
    tagBanks(i).io.dinb  := TagValidBundle(config.getTag(s2Addr), !s2Inv)
    tagBanks(i).io.addrb := s2Index
  }

  io.axi.ar <> axiBankRead.io.axi.ar
  io.axi.r <> axiBankRead.io.axi.r
  io.axi.aw <> axiBankWrite.io.axi.aw
  io.axi.w <> axiBankWrite.io.axi.w
  io.axi.b <> axiBankWrite.io.axi.b

  axiBankRead.io.axi.aw := DontCare
  axiBankRead.io.axi.w := DontCare
  axiBankRead.io.axi.b := DontCare
  axiBankWrite.io.axi.ar := DontCare
  axiBankWrite.io.axi.r := DontCare

  // process req
  /* Resolve combinational loop: remove dependency on io.core.flush
    That's to say there is no need to give ready signal when s1 flush. */
  // io.core.req.ready := !s1Stall
  io.core.req.ready := !s2Stall

  // process read
  io.core.resp.valid := s2Valid && !s2Inv && !s2Write && (state === writing || s2HitInCache) && !flushOutput
  io.core.resp.bits.loadData := Mux(state === writing, axiWriteToCache(s2BankIdx), s2Data)

  // flush
  when((state === waiting || state === refilling || state === receiving || s2Miss) && s2Flush) {
    // 原子操作，当进入 refilling 阶段则不打断而是只 flushOutput
    pipeFlush := 1.B
  }.elsewhen(!s2Stall) {
    // 当不再 Stall s2 时清空 pipeFlush
    pipeFlush := 0.B
  }

  val hitInform = !s2Stall && s1Valid && !s1Write && (s1HitTag || s1HitFIFO)
  val refillInform = (state === writing) && !s2Write && !pipeFlush

  io.core.inform.valid := hitInform || refillInform
  io.core.inform.bits := Mux(refillInform, s2Meta, s1Meta)

  io.release := axiBankWrite.io.release

  io.debug_s2_valid := s2Valid
  io.debug_s2_addr  := s2Addr
  io.debug_s2_state := state
}

class DCacheReqv2 extends Bundle {
  val addr      = UInt(addrWidth.W)

  val validMask = Vec(4, Bool())
  val writeData = UInt(dataWidth.W)
  val meta      = new MemReq
}

class DCacheRespv2 extends Bundle {
  val loadData = UInt(dataWidth.W)
  val meta     = new MemReq
}

class DCacheWithCorev2 extends Bundle {
  val req = Flipped(DecoupledIO(new DCacheReqv2))

  /** valid 标志一次有效的load数据, store类型的请求不做任何回应 */
  val resp = ValidIO(new DCacheRespv2)

  val inform = ValidIO(new InformMeta)

  val flush = Input(new ExecFlush(nBranchCount))
}
class DataCachev2(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays = config.numOfWays
  val nSets = config.numOfSets
  val nBanks = config.numOfBanks
  val io = IO(new Bundle {
    val core = new DCacheWithCorev2
    val axi = AXIIO.master()
    val inv = new CacheInvalidate
    val release = Output(Bool())

    val debug_s2_valid = Output(Bool())
    val debug_s2_addr  = Output(UInt(32.W))
    val debug_s2_state = Output(UInt(4.W))
  })
  // Stage0 -> a; Stage2 -> b;
  val tagBanks = for (i <- 0 until nWays)
    yield Module(new TypedDualPortAsyncRam(nSets, new TagValidBundle))

  // Stage1 -> a; Stage2 -> b;
  val dirtyBanks = for (i <- 0 until nWays)
    yield Module(new TypedSinglePortRam(nSets, Bool()))
  val dataBanks = for (i <- 0 until nWays)
    yield for (j <- 0 until nBanks)
      yield Module(new TypedDualPortRam(nSets, UInt(dataWidth.W)))

  // Write Back Queue
  val writeQueue = Module(new WriteBackQueue(capacity = 4))

  // LRU
  val lruUnit = Module(new PLRU(nSets, nWays))

  // AXI Read / Write
  val axiBankRead = Module(new AXIBankRead(axiId = 1.U))
  val axiBankWrite = Module(new AXIBankWrite(axiId = 1.U))

  // ---------------- state definitions -------------------------------
  val idle :: refilling :: waiting :: receiving :: writing :: invalidating :: invalidated :: Nil = Enum(7)
  val state = RegInit(idle)
  val nxtState = WireInit(state)
  val wb_idle :: wb_writing :: Nil = Enum(2)
  val wbState = RegInit(wb_idle)

  // stage 0 (access tagBanks && WriteBackQueue)
  val s1Stall = WireInit(0.B)

  val s1Valid = RegInit(0.B)
  val s1Addr = RegInit(0.U(addrWidth.W))
  val s1Meta = RegInit(0.U.asTypeOf(new MemReq))
  val s1Write = RegInit(0.B)
  val s1WriteMask = RegInit(0.U.asTypeOf(Vec(4, Bool())))
  val s1WriteData = RegInit(0.U(dataWidth.W))
  val s1HitTag = RegInit(0.B)
  val s1HitWay = RegInit(0.U(log2Ceil(nWays).W))
  val s1Data = WireInit(0.U(dataWidth.W)) // important
  val s1HitFIFO = RegInit(0.B)
  val s1FIFOData = RegInit(0.U(dataWidth.W))

  val s1Inform = WireInit(0.U.asTypeOf(new InformMeta))
  s1Inform.writeEnable  := s1Meta.writeEnable
  s1Inform.writeRegAddr := s1Meta.writeRegAddr

  // flush read signals
  val reqWrite = io.core.req.bits.validMask.reduce(_ || _)
  val reqFlush = io.core.req.valid && !reqWrite && DataCacheUtils.needFlush(io.core.flush, io.core.req.bits.meta)
  val s1Flush = s1Valid && !s1Write && DataCacheUtils.needFlush(io.core.flush, s1Meta)

  // access tagBanks in the first cycle (no latency)
  val hitTag = WireInit(0.U.asTypeOf(Vec(nWays, Bool())))
  for (i <- 0 until nWays) {
    tagBanks(i).io.wea := 0.B
    tagBanks(i).io.dina := 0.U.asTypeOf(new TagValidBundle)
    tagBanks(i).io.addra := cacheConfig.getIndex(io.core.req.bits.addr)
    hitTag(i) := tagBanks(i).io.douta.valid &&
      tagBanks(i).io.douta.tag === cacheConfig.getTag(io.core.req.bits.addr)
  }

  // access datBanks first
  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.wea := 0.B
      dataBanks(i)(j).io.dina := 0.U
      dataBanks(i)(j).io.addra := Mux(s1Stall, cacheConfig.getIndex(s1Addr),
        cacheConfig.getIndex(io.core.req.bits.addr))
    }
  }

  // access FIFO in the first cycle (no latency)
  writeQueue.io.req.valid := io.core.req.fire
  writeQueue.io.req.bits.addr := io.core.req.bits.addr
  writeQueue.io.req.bits.data := io.core.req.bits.writeData
  writeQueue.io.req.bits.writeMask := io.core.req.bits.validMask

  // s1HitTag && s1HitWay will be assigned below
  when(!s1Stall) {
    // no flush in write
    s1Valid := io.core.req.fire && !reqFlush
    s1Addr := io.core.req.bits.addr
    s1Meta := io.core.req.bits.meta
    s1Write := reqWrite
    s1WriteMask := io.core.req.bits.validMask
    s1WriteData := io.core.req.bits.writeData
    s1HitFIFO := writeQueue.io.resp.valid
    s1FIFOData := writeQueue.io.resp.bits
  }

  val s1BankIdx = cacheConfig.getBankIndex(s1Addr)
  val s1DataOut = VecInit(for (i <- 0 until nWays)
    yield VecInit(for (j <- 0 until nBanks)
      yield dataBanks(i)(j).io.douta))
  s1Data := s1DataOut(s1HitWay)(s1BankIdx)
  //  s1HitTag := hitTag.reduce(_ || _)
  //  s1HitWay := OHToUInt(hitTag)

  val s1WriteBack = DataCacheUtils.generateWriteBack(s1Data, s1WriteData, s1WriteMask)

  // stage 1 (process dataBanks)
  val s2Stall = WireInit(0.B)

  val s2Valid      = RegInit(0.B)
  val s2Inv        = RegInit(0.B)
  val s2Addr       = RegInit(0.U(addrWidth.W))
  val s2Meta       = RegInit(0.U.asTypeOf(new MemReq))
  val s2Write      = RegInit(0.B)
  val s2WriteData  = RegInit(0.U(dataWidth.W))
  val s2WriteMask  = RegInit(0.U.asTypeOf(Vec(4, Bool())))
  val s2HitInCache = RegInit(0.B)
  val s2HitWay     = RegInit(0.U)
  val s2Data       = RegInit(0.U(dataWidth.W))
  val s2WBValid    = RegInit(0.B)
  val s2WriteBack  = RegInit(0.U(dataWidth.W))

  val s2HitInRefill = RegInit(0.B)

  val s2Inform = WireInit(0.U.asTypeOf(new InformMeta))
  s2Inform.writeEnable := s2Meta.writeEnable
  s2Inform.writeRegAddr := s2Meta.writeRegAddr

  val s2Index = cacheConfig.getIndex(s2Addr)
  val s2BankIdx = cacheConfig.getBankIndex(s2Addr)

  // adjacant write
  val s1AdjacantWriteBack = DataCacheUtils.generateWriteBack(s2WriteBack, s1WriteData, s1WriteMask)


  lruUnit.io.getLRU.index := s2Index
  lruUnit.io.getLRU.valid := 1.B
  val s2LRUWay = lruUnit.io.getLRU.way

  lruUnit.io.update.valid := (s2HitInCache && s2Valid) || (state === writing && s2Valid)
  lruUnit.io.update.bits.way := Mux(state === idle, s2HitWay, s2LRUWay)
  lruUnit.io.update.bits.index := cacheConfig.getIndex(s2Addr)

  val s2Miss = s2Valid && !s2HitInCache && !s2HitInRefill
  val s2InvComplete = RegInit(0.B)

  // flush read signals
  val s2Flush = s2Valid && !s2Inv && !s2Write && DataCacheUtils.needFlush(io.core.flush, s2Meta)
  val pipeFlush = RegInit(0.B)
  val flushOutput = pipeFlush || s2Flush

  // Stall
  s1Stall := s2Stall && !s1Flush
  s2Stall := (state =/= idle) || (s2Valid && !s2Inv && s2Miss) || (s2Inv && !s2InvComplete)

  val s2DirtyOut = VecInit(for (i <- 0 until nWays) yield dirtyBanks(i).io.dataOut)
  val s2DataOut  =
    VecInit(for (i <- 0 until nWays) yield
      VecInit(for (j <- 0 until nBanks) yield
        dataBanks(i)(j).io.doutb))
  val s2TagOut = VecInit(for (i <- 0 until nWays) yield tagBanks(i).io.doutb.tag)

  val s2Dirty     = s2DirtyOut(s2LRUWay)
  val s2Victim    = s2DataOut(s2LRUWay)
  val s2VictimTag = s2TagOut(s2LRUWay)

  /* Process adjacant problem (s2 write && s1 read the same place)
     We can process cache miss in the same way, but it is complex.
    */
  // read / write after write
  val s1s2DataAdjacent =
  s1Valid && s2Valid && s2WBValid && (s2Addr === s1Addr)

  val s1s2HitAdjacantIn = (state === writing) &&
    cacheConfig.getLineAddr(s1Addr) === cacheConfig.getLineAddr(s2Addr)

  val s1s2HitAdjacantOut = (state === writing) && s1HitTag && s1HitWay === s2LRUWay &&
    cacheConfig.getIndex(s1Addr) === cacheConfig.getIndex(s2Addr)

  when(s1s2HitAdjacantIn) {
    s1HitTag := 1.B
    s1HitWay := s2LRUWay
  }.elsewhen(s1s2HitAdjacantOut) {
    s1HitTag := 0.B
  }.elsewhen(!s1Stall) {
    s1HitTag := hitTag.reduce(_ || _)
    s1HitWay := OHToUInt(hitTag)
  }

  /////////////////////////////////////////////////////////////////////

  io.inv.req.ready := !s2Valid && !s1Valid
  val reqInv = io.inv.req.fire

  io.inv.resp := s2InvComplete

  when(!s2Stall) {
    s2Valid      := (s1Valid && !s1Flush) || reqInv
    s2Inv        := reqInv
    s2Addr       := Mux(reqInv, io.inv.req.bits, s1Addr)
    s2Meta       := s1Meta
    s2Write      := s1Write
    s2WriteData  := s1WriteData
    s2WriteMask  := s1WriteMask
    s2HitInCache := (s1HitTag || s1HitFIFO)
    s2HitWay     := s1HitWay
    s2Data       := MuxCase(s1Data, Seq(
      s1s2DataAdjacent -> s2WriteBack,
      s1HitFIFO -> s1FIFOData
    ))
    s2WBValid    := s1HitTag && s1Write && s1Valid
    s2WriteBack  := Mux(s1s2DataAdjacent, s1AdjacantWriteBack, s1WriteBack)

  }

  // refill control
  when(!s2Stall) {
    s2HitInRefill := 0.B
  }.elsewhen(nxtState === writing) {
    s2HitInRefill := 1.B
  }

  // invalidate control
  when (!s2Stall) {
    s2InvComplete := 0.B
  } .elsewhen(nxtState === idle && state === invalidated) {
    s2InvComplete := 1.B
  }

  // ---------------- refilling writeQueue push (writeback) -------------------
  val axiWriteToRead = WireInit(0.B)

  val writeQueueEnqReady = writeQueue.io.enq.ready
  val writeQueueEnqBlocked = s2Dirty && !writeQueueEnqReady

  // victim
  val victimEnqValid = (state === refilling) && s2Dirty
  val victimEnqAddr  = Cat(s2VictimTag, s2Index)
  val victimEnqData  = s2Victim

  // invalidate
  val invWay = RegInit(0.U(log2Up(cacheConfig.numOfWays).W))

  val invEnqValid = (state === invalidating) && s2DirtyOut(invWay)
  val invEnqAddr  = Cat(s2TagOut(invWay), s2Index)
  val invEnqData  = s2DataOut(invWay)

  writeQueue.io.enq.valid     := invEnqValid || victimEnqValid
  writeQueue.io.enq.bits.addr := Mux(state === invalidating, invEnqAddr, victimEnqAddr)
  writeQueue.io.enq.bits.data := Mux(state === invalidating, invEnqData, victimEnqData)

  // ---------------- axiBankRead req ----------------------------------
  val axiReadReady = axiBankRead.io.req.ready
  val axiReadAddr = Cat(cacheConfig.getLineAddr(s2Addr), 0.U((32 - cacheConfig.lineAddrLen).W))

  axiBankRead.io.req.valid := (state === waiting)
  axiBankRead.io.req.bits := axiReadAddr

  // ----------------- writeQueue pop && axiBankWrite req ---------------------------
  val writeQueueDeqValid = writeQueue.io.deq.valid
  val axiWriteReady = axiBankWrite.io.req.ready

  val axiWriteAddr = RegInit(0.U(addrWidth.W))
  val axiWriteLine = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))

  writeQueue.io.deq.ready := (wbState === wb_idle) && axiWriteReady

  axiBankWrite.io.req.valid := (wbState === wb_idle) && writeQueueDeqValid
  axiBankWrite.io.req.bits.data := writeQueue.io.deq.bits.data
  axiBankWrite.io.req.bits.addr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))

  // ----------------- axi Read to Cache -------------------------------
  val axiWriteToCache = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))
  val axiReadLine = WireInit(axiBankRead.io.resp.bits)
  when(axiWriteToRead) {
    axiReadLine := axiWriteLine
  }

  val axiNewCacheLine = WireInit(axiReadLine)
  when(s2Write) {
    axiNewCacheLine(s2BankIdx) := DataCacheUtils.generateWriteBack(axiReadLine(s2BankIdx), s2WriteData, s2WriteMask)
  }

  // 注意！ refilling 状态机和 writeback 状态机可能有严重的竞争问题！
  // 当数据正在写回的同时请求同一个数据会得到错误的老数据 ！！！
  axiWriteToRead := wbState =/= wb_idle && axiWriteAddr === axiReadAddr && (writeQueueEnqReady || !s2Dirty)
  switch(state) {
    is(idle) {
      // 若 s2Flush 则不进入refilling阶段
      when(!s2Inv && s2Miss) {
        nxtState := refilling
      } .elsewhen(s2Inv && !s2InvComplete) {
        invWay   := (cacheConfig.numOfWays-1).U
        nxtState := invalidating
      }
    }
    is(refilling) {
      when (!s2Dirty || writeQueueEnqReady) {
        when (axiWriteToRead) {
          nxtState        := writing
          axiWriteToCache := axiNewCacheLine
        } .otherwise {
          nxtState        := waiting
        }
      }
    }
    is(waiting) {
      when(axiBankRead.io.req.fire) {
        nxtState := receiving
      }
    }
    is(receiving) {
      when(axiBankRead.io.resp.fire) {
        nxtState := writing
        axiWriteToCache := axiNewCacheLine
      }
    }
    is(writing) {
      nxtState := idle
    }
    is(invalidating) {
      when (writeQueueEnqReady) {
        when (invWay === 0.U) {
          nxtState      := invalidated
        } .otherwise {
          invWay        := invWay - 1.U
        }
      }
    }
    is(invalidated) {
      when (writeQueue.io.empty && wbState === idle) {
        nxtState := idle
      }
    }
  }
  state := nxtState

  switch(wbState) {
    is(wb_idle) {
      when(axiBankWrite.io.req.fire) {
        wbState := wb_writing
        axiWriteAddr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))
        axiWriteLine := writeQueue.io.deq.bits.data
      }
    }
    is(wb_writing) {
      when(axiBankWrite.io.resp) {
        wbState := wb_idle
      }
    }
  }

  // process write
  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.web :=
        (state === writing && i.U === s2LRUWay) ||
          (s2Valid && s2WBValid && i.U === s2HitWay && j.U === s2BankIdx)

      dataBanks(i)(j).io.dinb := Mux(
        state === writing,
        axiWriteToCache(j),
        s2WriteBack
      )
      dataBanks(i)(j).io.addrb := s2Index
    }

    dirtyBanks(i).io.write :=
      (state === writing && i.U === s2LRUWay) ||
        (state === invalidated) ||
        (s2Valid && s2WBValid && i.U === s2HitWay)

    dirtyBanks(i).io.dataIn := MuxCase(0.B, Seq(
      (state === writing)     -> s2Write,
      (state === invalidated) -> 0.B,
      (state === idle)        -> 1.B
    ))
    dirtyBanks(i).io.addr := s2Index

    tagBanks(i).io.web   :=
      (state === writing && i.U === s2LRUWay) || (state === invalidated)
    tagBanks(i).io.dinb  := TagValidBundle(config.getTag(s2Addr), !s2Inv)
    tagBanks(i).io.addrb := s2Index
  }

  io.axi.ar <> axiBankRead.io.axi.ar
  io.axi.r <> axiBankRead.io.axi.r
  io.axi.aw <> axiBankWrite.io.axi.aw
  io.axi.w <> axiBankWrite.io.axi.w
  io.axi.b <> axiBankWrite.io.axi.b

  axiBankRead.io.axi.aw := DontCare
  axiBankRead.io.axi.w := DontCare
  axiBankRead.io.axi.b := DontCare
  axiBankWrite.io.axi.ar := DontCare
  axiBankWrite.io.axi.r := DontCare

  // process req
  /* Resolve combinational loop: remove dependency on io.core.flush
    That's to say there is no need to give ready signal when s1 flush. */
  // io.core.req.ready := !s1Stall
  io.core.req.ready := !s2Stall

  // process read
  io.core.resp.valid := s2Valid && !s2Inv && !s2Write && (state === writing || s2HitInCache) && !flushOutput
  io.core.resp.bits.loadData := Mux(state === writing, axiWriteToCache(s2BankIdx), s2Data)
  io.core.resp.bits.meta := s2Meta

  // flush
  when((state === waiting || state === refilling || state === receiving || s2Miss) && s2Flush) {
    // 原子操作，当进入 refilling 阶段则不打断而是只 flushOutput
    pipeFlush := 1.B
  }.elsewhen(!s2Stall) {
    // 当不再 Stall s2 时清空 pipeFlush
    pipeFlush := 0.B
  }

  val hitInform = !s2Stall && s1Valid && !s1Write && (s1HitTag || s1HitFIFO)
  val refillInform = (state === writing) && !s2Write && !pipeFlush

  io.core.inform.valid := hitInform || refillInform
  io.core.inform.bits := Mux(refillInform, s2Inform, s1Inform)

  io.release := axiBankWrite.io.release

  io.debug_s2_valid := s2Valid
  io.debug_s2_addr  := s2Addr
  io.debug_s2_state := state
}

class UnCacheWrite extends Bundle {
  val addr      = UInt(addrWidth.W)
  val validMask = Vec(4, Bool())
  val writeData = UInt(dataWidth.W)
}

class DCacheWithLSU extends Bundle {
  val valid     = Input(Bool())
  val write     = Input(Bool())
  val inval     = Input(Bool())
  // 对于 load 指令为虚拟地址，对于 store 指令为实际地址。
  val addr      = Input(UInt(addrWidth.W))
  val validMask = Input(Vec(4, Bool()))
  val writeData = Input(UInt(dataWidth.W))

  val unCacheWrite = Flipped(Decoupled(new UnCacheWrite))
  val unCacheNum   = Input(UInt(log2Ceil(unbufferAmount).W))

  // stall
  val stall     = Output(Bool())
  val resp      = Output(UInt(dataWidth.W))
}

class DataCachev3(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays  = config.numOfWays
  val nSets  = config.numOfSets
  val nBanks = config.numOfBanks
  val io = IO(new Bundle {
    val lsu         = new DCacheWithLSU
    val tlbDataResp = Flipped(new TLBDataResp)

    val stallReq    = Output(Bool())
    val informValid = Output(Bool())

    val dcacheAxi      = AXIIO.master()
    val dcacheRelease  = Output(Bool())
    val uncacheAxi     = AXIIO.master()
    val uncacheRelease = Output(Bool())

    val debug_dcache_state = Output(UInt(4.W))
    val debug_out_num      = Output(UInt(3.W))
  })
  // banks
  val validBanks = for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, Bool()))
  val dirtyBanks = for (i <- 0 until nWays) yield Module(new TypedSinglePortRam(nSets, Bool()))

  val tagBanks = for (i <- 0 until nWays)
    yield Module(new TypedDualPortAsyncRam(nSets, UInt(cacheConfig.tagLen.W)))
  val dataBanks = for (i <- 0 until nWays)
    yield for (j <- 0 until nBanks)
      yield Module(new TypedDualPortByteWriteRam(nSets))

  val lruUnit = Module(new PLRU(nSets, nWays))

  val writeQueue = Module(new WriteBackQueuev2())

  // axi
  val axiBankRead  = Module(new AXIBankRead(axiId = 1.U))
  val axiBankWrite = Module(new AXIBankWrite(axiId = 1.U))
  val axiRead      = Module(new AXIRead(axiId = 2.U))
  val axiWrite     = Module(new AXIOutstandingWrite(axiId = 2.U))

  // mem1
  for (i <- 0 until nWays) {
    tagBanks(i).io.wea   := 0.B
    tagBanks(i).io.dina  := 0.U
    tagBanks(i).io.addra := cacheConfig.getIndex(io.lsu.addr)
    validBanks(i).io.wea   := 0.B
    validBanks(i).io.dina  := 0.U
    validBanks(i).io.addra := cacheConfig.getIndex(io.lsu.addr)
  }

  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.dina  := 0.U
      dataBanks(i)(j).io.addra := cacheConfig.getIndex(io.lsu.addr)
    }
  }


  val matValids = Wire(Vec(nWays, Bool()))
  val matReads  = Wire(Vec(nWays, Bool()))
  val matWrites = Wire(Vec(nWays, Bool()))

  for (i <- 0 until nWays) {
    matValids(i) := validBanks(i).io.douta
    matReads(i)  := tagBanks(i).io.douta === io.tlbDataResp.pfn
    matWrites(i) := tagBanks(i).io.douta === cacheConfig.getTag(io.lsu.addr)
  }
  // mem2 Stage
  val idle :: cacheCheck :: cacheWaitAxi :: cacheReceive :: cacheRefill :: uncacheWaitAxi :: uncacheReceive :: invExe :: invWait :: reseting :: Nil = Enum(10)
  val state = RegInit(reseting)
  val resetIndex = RegInit((nSets-1).U(cacheConfig.indexLen.W))

  val wb_idle :: wb_writing :: Nil = Enum(2)
  val wbState = RegInit(wb_idle)

  val s2Valid     = RegInit(0.B)
  val s2Write     = RegInit(0.B)
  val s2Inv       = RegInit(0.B)
  val s2ReadAddr  = RegInit(0.U(addrWidth.W))
  val s2WriteAddr = RegInit(0.U(addrWidth.W))
  val s2WriteMask = RegInit(0.U.asTypeOf(Vec(4, Bool())))
  val s2WriteData = RegInit(0.U(dataWidth.W))
  val s2UnCache   = RegInit(0.B)
  val s2MatValids = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s2MatReads  = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s2MatWrites = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))

  val s2Addr    = Mux(s2Write, s2WriteAddr, s2ReadAddr)
  val s2Index   = cacheConfig.getIndex(s2WriteAddr)
  val s2BankIdx = cacheConfig.getBankIndex(s2WriteAddr)

  val s2Stall     = WireDefault(0.B)

  when (!s2Stall) {
    s2Valid     := io.lsu.valid
    s2Write     := io.lsu.write
    s2Inv       := io.lsu.inval
    s2ReadAddr  := Cat(io.tlbDataResp.pfn, io.lsu.addr(11, 0))
    s2WriteAddr := io.lsu.addr
    s2WriteMask := io.lsu.validMask
    s2WriteData := io.lsu.writeData
    s2UnCache   := Mux(io.lsu.write, 0.B, io.tlbDataResp.uncache)
    s2MatValids := matValids
    s2MatReads  := matReads
    s2MatWrites := matWrites
  }

  // mem2
  // control signals
  val s2HitRefill  = RegInit(0.B)
  val s2HitUnCache = RegInit(0.B)
  val s2HitInv     = RegInit(0.B)

  when(!s2Stall) {
    s2HitRefill  := 0.B
    s2HitUnCache := 0.B
    s2HitInv     := 0.B
  }

  val s2HitReads  = Wire(Vec(nWays, Bool()))
  val s2HitWrites = Wire(Vec(nWays, Bool()))

  for (i <- 0 until nWays) {
    s2HitReads(i)  := s2MatValids(i) && s2MatReads(i)
    s2HitWrites(i) := s2MatValids(i) && s2MatWrites(i)
  }

  val isCache   = s2Valid && !s2Inv && !s2UnCache
  val isUnCache = s2Valid && s2UnCache
  val isInv     = s2Valid && s2Inv

  val s2HitRead     = isCache && !s2Write && s2HitReads.reduce(_ || _)
  val s2HitReadWay  = OHToUInt(s2HitReads)

  val s2HitWrite    = isCache && s2Write && s2HitWrites.reduce(_ || _)
  val s2HitWriteWay = OHToUInt(s2HitWrites)

  val s2MissCache   = isCache && !s2HitRead && !s2HitWrite && !s2HitRefill
  val s2MissUnCache = isUnCache && !s2HitUnCache
  val s2MissInv     = isInv && !s2HitInv

  val s2Hit = s2HitRead || s2HitWrite || s2HitRefill || s2HitUnCache || s2HitInv

  s2Stall := (s2Valid && !s2Hit) || (state =/= idle)
  ////////////////////////////// LRU ///////////////////////////////////////////////////
  lruUnit.io.getLRU.index := s2Index
  lruUnit.io.getLRU.valid := 1.B
  val s2LRUWay            = lruUnit.io.getLRU.way

  lruUnit.io.update.valid      := (state === idle) && (s2HitRead || s2HitWrite || s2HitRefill)
  lruUnit.io.update.bits.index := s2Index
  lruUnit.io.update.bits.way   :=
    MuxCase(0.U, Seq(
      s2HitRead   -> s2HitReadWay,
      s2HitWrite  -> s2HitWriteWay,
      s2HitRefill -> s2LRUWay
    ))

  //////////////////////////// hit read / hit write ///////////////////////////////////

  val hitDataOut = Wire(Vec(nWays, Vec(nBanks, UInt(dataWidth.W))))
  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      hitDataOut(i)(j) := dataBanks(i)(j).io.douta
    }
  }

  val s2HitWriteData = DataCacheUtils.generateWriteBack(hitDataOut(s2HitWriteWay)(s2BankIdx), s2WriteData, s2WriteMask)

  ////////////////////////////// write Queue ///////////////////////////////////////////
  val s2DirtyOut = VecInit(for (i <- 0 until nWays) yield dirtyBanks(i).io.dataOut)
  val s2DataOut =
    VecInit(for (i <- 0 until nWays) yield
      VecInit(for (j <- 0 until nBanks) yield
        dataBanks(i)(j).io.doutb))
  val s2TagOut = VecInit(for (i <- 0 until nWays) yield tagBanks(i).io.doutb)

  val s2VictimDirty = s2DirtyOut(s2LRUWay)
  val s2Victim      = s2DataOut(s2LRUWay)
  val s2VictimTag   = s2TagOut(s2LRUWay)

  // victim
  val victimEnqValid = (state === cacheCheck) && s2VictimDirty
  val victimEnqAddr  = Cat(s2VictimTag, s2Index)
  val victimEnqData  = s2Victim

  // invalidate
  val invWay = RegInit(0.U(log2Up(cacheConfig.numOfWays).W))

  val invEnqValid = (state === invExe) && s2DirtyOut(invWay)
  val invEnqAddr  = Cat(s2TagOut(invWay), s2Index)
  val invEnqData  = s2DataOut(invWay)

  writeQueue.io.enq.valid     := invEnqValid || victimEnqValid
  writeQueue.io.enq.bits.addr := Mux(state === invExe, invEnqAddr, victimEnqAddr)
  writeQueue.io.enq.bits.data := Mux(state === invExe, invEnqData, victimEnqData)
  ////////////////////////// axi Bank Read req /////////////////////
  val axiReadReady = axiBankRead.io.req.ready
  val axiReadAddr  = Cat(cacheConfig.getLineAddr(s2Addr), 0.U((32 - cacheConfig.lineAddrLen).W))

  axiBankRead.io.req.valid := (state === cacheWaitAxi)
  axiBankRead.io.req.bits  := axiReadAddr
  ////////////////////////// axi Bank Read resp ////////////////////
  val axiWriteToCache = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))
  val axiReadLine = WireInit(axiBankRead.io.resp.bits)
  val axiNewCacheLine = WireInit(axiReadLine)
  when(s2Write) {
    axiNewCacheLine(s2BankIdx) := DataCacheUtils.generateWriteBack(axiReadLine(s2BankIdx), s2WriteData, s2WriteMask)
  }

  ////////////////////////// axi Bank Write resp ///////////////////
  val writeQueueDeqValid = writeQueue.io.deq.valid
  val axiWriteReady      = axiBankWrite.io.req.ready

//  val axiWriteAddr = RegInit(0.U(addrWidth.W))
//  val axiWriteLine = RegInit(0.U.asTypeOf(Vec(nBanks, UInt(dataWidth.W))))

  writeQueue.io.deq.ready       := (wbState === wb_idle) && axiWriteReady

  axiBankWrite.io.req.valid     := (wbState === wb_idle) && writeQueueDeqValid
  axiBankWrite.io.req.bits.data := writeQueue.io.deq.bits.data
  axiBankWrite.io.req.bits.addr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))

  ///////////////////////// uncache read / write ///////////////////
  val s2UncacheData = RegInit(0.U(dataWidth.W))

  axiRead.io.req.valid := (state === uncacheWaitAxi) && (axiWrite.io.writeNum === 0.U) && (io.lsu.unCacheNum === 0.U)
  axiRead.io.req.bits  := s2ReadAddr

  axiWrite.io.req.valid      := io.lsu.unCacheWrite.valid
  axiWrite.io.req.bits.addr  := io.lsu.unCacheWrite.bits.addr
  axiWrite.io.req.bits.valid := io.lsu.unCacheWrite.bits.validMask
  axiWrite.io.req.bits.data  := io.lsu.unCacheWrite.bits.writeData
  io.lsu.unCacheWrite.ready  := axiWrite.io.req.ready

  val nextHitRefill = WireInit(0.B)

  switch (state) {
    is (idle) {
      when (s2MissCache) {
        state := cacheCheck
      }
      when (s2MissUnCache) {
        state := uncacheWaitAxi
      }
      when (s2MissInv) {
        state  := invExe
        invWay := (cacheConfig.numOfWays-1).U
      }
    }
    is (cacheCheck) {
      state := cacheWaitAxi
    }
    is (cacheWaitAxi) {
      when (axiBankRead.io.req.fire) {
        state := cacheReceive
      }
    }
    is (cacheReceive) {
      when (axiBankRead.io.resp.fire) {
        state           := cacheRefill
        axiWriteToCache := axiNewCacheLine
      }
    }
    is (cacheRefill) {
      when (wbState === wb_idle && writeQueue.io.empty) {
        state       := idle
        s2HitRefill := 1.B
        nextHitRefill := 1.B
      }
    }
    is (uncacheWaitAxi) {
      when (axiRead.io.req.fire) {
        state := uncacheReceive
      }
    }
    is (uncacheReceive) {
      when (axiRead.io.resp.fire) {
        state         := idle
        s2HitUnCache  := 1.B
        s2UncacheData := axiRead.io.resp.bits
      }
    }
    is (invExe) {
      when (writeQueue.io.enq.ready) {
        when (invWay === 0.U) {
          state  := invWait
        } .otherwise {
          invWay := invWay -1.U
        }
      }
    }
    is (invWait) {
      when(writeQueue.io.empty && wbState === idle) {
        state    := idle
        s2HitInv := 1.B
      }
    }
    is(reseting) {
      when(resetIndex === 0.U) {
        state := idle
      }.otherwise {
        resetIndex := resetIndex - 1.U
      }
    }
  }

  switch (wbState) {
    is(wb_idle) {
      when (axiBankWrite.io.req.fire) {
        wbState      := wb_writing
//        axiWriteAddr := Cat(writeQueue.io.deq.bits.addr, 0.U((32 - cacheConfig.lineAddrLen).W))
//        axiWriteLine := writeQueue.io.deq.bits.data
      }
    }

    is (wb_writing) {
      when (axiBankWrite.io.resp) {
        wbState := wb_idle
      }
    }
  }

  for (i <- 0 until nWays) {
    for (j <- 0 until nBanks) {
      dataBanks(i)(j).io.web := MuxCase(VecInit(Seq.fill(4)(0.B)), Seq(
        (state === cacheRefill && i.U === s2LRUWay)                                -> VecInit(Seq.fill(4)(1.B)),
        (s2Valid &&  s2Write && !s2UnCache && s2HitWrites(i) && j.U === s2BankIdx) -> s2WriteMask
      ))

      dataBanks(i)(j).io.dinb  := Mux(
        state === cacheRefill,
        axiWriteToCache(j),
        s2WriteData
      )
      dataBanks(i)(j).io.addrb := s2Index
    }

    tagBanks(i).io.web  :=
      (state === cacheRefill && i.U === s2LRUWay)
    tagBanks(i).io.dinb  := config.getTag(s2Addr)
    tagBanks(i).io.addrb := s2Index

    dirtyBanks(i).io.write :=
      (state === cacheRefill && i.U === s2LRUWay) ||
        (s2HitWrite && i.U === s2HitWriteWay) ||
        (state === invWait) ||
        (state === reseting)

    dirtyBanks(i).io.addr := Mux(state === reseting, resetIndex, s2Index)
    dirtyBanks(i).io.dataIn :=
      MuxCase(0.B, Seq(
        (state === cacheRefill) -> s2Write,
        (state === idle) -> 1.B,
        (state === invWait) -> 0.B
      ))

    validBanks(i).io.web :=
      (state === cacheRefill && i.U === s2LRUWay) ||
        (state === invWait) ||
        (state === reseting)
    validBanks(i).io.addrb := Mux(state === reseting, resetIndex, s2Index)
    validBanks(i).io.dinb  := !s2Inv && (state =/= reseting)


  }

  io.lsu.resp := MuxCase(0.U, Seq(
    s2HitRead    -> hitDataOut(s2HitReadWay)(s2BankIdx),
    s2HitRefill  -> axiWriteToCache(s2BankIdx),
    s2HitUnCache -> s2UncacheData
  ))

  io.dcacheAxi.ar <> axiBankRead.io.axi.ar
  io.dcacheAxi.r  <> axiBankRead.io.axi.r
  io.dcacheAxi.aw <> axiBankWrite.io.axi.aw
  io.dcacheAxi.w  <> axiBankWrite.io.axi.w
  io.dcacheAxi.b  <> axiBankWrite.io.axi.b

  axiBankRead.io.axi.aw  := DontCare
  axiBankRead.io.axi.w   := DontCare
  axiBankRead.io.axi.b   := DontCare
  axiBankWrite.io.axi.ar := DontCare
  axiBankWrite.io.axi.r  := DontCare

  io.uncacheAxi.ar <> axiRead.io.axi.ar
  io.uncacheAxi.r  <> axiRead.io.axi.r
  io.uncacheAxi.aw <> axiWrite.io.axi.aw
  io.uncacheAxi.w  <> axiWrite.io.axi.w
  io.uncacheAxi.b  <> axiWrite.io.axi.b

  axiRead.io.axi.aw  := DontCare
  axiRead.io.axi.w   := DontCare
  axiRead.io.axi.b   := DontCare
  axiWrite.io.axi.ar := DontCare
  axiWrite.io.axi.r  := DontCare

  io.dcacheRelease  := axiBankWrite.io.release
  io.uncacheRelease := axiWrite.io.release

  io.lsu.stall := s2Stall
  io.stallReq  := s2Stall && !s2Write

  io.debug_dcache_state := state
  io.debug_out_num      := axiWrite.io.writeNum

  val informValid = RegNext(io.uncacheAxi.r.fire || nextHitRefill, 0.B)
  io.informValid := informValid
}