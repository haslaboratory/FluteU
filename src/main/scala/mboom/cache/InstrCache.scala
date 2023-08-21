package mboom.cache

import chisel3._
import chisel3.util._
import mboom.axi.AXIIO
import mboom.cache.axi.AXIBankRead
import mboom.cache.components.{RefillUnit, RefillUnitFaker, TagValidBundle}
import mboom.cache.lru.{LRU, PLRU}
import mboom.components.{TypedSinglePortAsyncRam, TypedSinglePortRam}
import mboom.components.{TypedDualPortAsyncRam, TypedDualPortRam}
import mboom.config.CPUConfig._
import mboom.config.CacheConfig
import mboom.util.AddrMap
import mboom.mmu.{TLBInstrEx, TLBInstrResp, TLBWithICache}

class ICacheReq extends Bundle {
  val addr = UInt(addrWidth.W)
}

class ICacheResp extends Bundle {
  val data = Vec(fetchGroupSize, UInt(dataWidth.W))
}

class ICacheWithCore extends Bundle {
  val req = Flipped(DecoupledIO(new ICacheReq))
  val resp = ValidIO(new ICacheResp)
  val flush = Input(Bool())
}
// Take cache line into account
class ICacheResp1 extends Bundle {
  val data   = Vec(fetchGroupSize+2, UInt(dataWidth.W))
  val valid  = Vec(fetchGroupSize+2, Bool())
  val except = new TLBInstrEx
}

class ICacheWithCore1 extends Bundle {
  val req   = Flipped(DecoupledIO(new ICacheReq))

  val resp  = DecoupledIO(new ICacheResp1) // important

  val flush = Input(Bool())
}

class ITLBReq extends Bundle {
  val vpn   = UInt(20.W)
}

class ITLBResp extends Bundle {
  val valid   = Output(Bool())
  val pfn     = Output(UInt(20.W))
  val uncache = Output(Bool())
  val instrEx = Output(new TLBInstrEx)
}

class ITLB extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Valid(new ITLBReq))
    val resp = Decoupled(new ITLBResp)

    val tlbInstrReq = Output(UInt(20.W))
    val tlbInstrResp = Flipped(new TLBInstrResp)

    //
    val fence = Input(Bool())
    val flush = Input(Bool())
  })

  val idle :: refilling :: informing :: Nil = Enum(3)
  val state = RegInit(idle)

  val vpn   = RegInit(0.U(20.W))
  val pfn   = RegInit(0.U(20.W))
  val uncache = RegInit(0.B)
  val valid = RegInit(0.B)
  // temp data
  val refillReq = RegInit(0.U(20.W))
  val instrEx   = RegInit(0.U.asTypeOf(new TLBInstrEx))

  // state === idle
  val mapped = (!io.req.bits.vpn(19)) || (io.req.bits.vpn(19, 18) === "b11".U(2.W))
  val hit    = (io.req.bits.vpn === vpn) && valid
  // state === refilling
  io.tlbInstrReq := refillReq

  when (io.fence) {
    valid := 0.B
  } .elsewhen(state === refilling) {
    vpn     := refillReq
    pfn     := io.tlbInstrResp.pfn
    valid   := io.tlbInstrResp.valid
    uncache := io.tlbInstrResp.uncache
  }

  // reforming
  io.resp.valid        := ((!mapped || hit) && (state === idle)) || (state === informing)
  io.resp.bits.pfn     := Mux(mapped, pfn, Cat(0.U(3.W), io.req.bits.vpn(16, 0)))
  io.resp.bits.uncache := 0.B
//    (io.req.bits.vpn(19, 17) === "b101".U(3.W)) ||
//      (mapped && uncache)
  io.resp.bits.instrEx := instrEx
  io.resp.bits.valid   := (state === idle) || (!instrEx.invalid && !instrEx.refill)

  switch (state) {
    is(idle) {
      when (io.req.valid && !io.flush && mapped && !hit) {
        state     := refilling
        refillReq := io.req.bits.vpn
      }
    }
    is (refilling) {
      when (io.flush) {
        state   := idle
        instrEx := 0.U.asTypeOf(new TLBInstrEx)
      } .otherwise {
        state   := informing
        instrEx := io.tlbInstrResp.except
      }
    }
    is (informing) {
      when (io.resp.ready || io.flush) {
        state   := idle
        instrEx := 0.U.asTypeOf(new TLBInstrEx)
      }
    }
  }
}

class InstrCache(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays           = config.numOfWays
  val nSets           = config.numOfSets
  val nBanks          = config.numOfBanks

  val io = IO(new Bundle {
    val core = new ICacheWithCore1
    val tlb  = Flipped(new TLBWithICache)
    val inv  = new CacheInvalidate
    val axi  = AXIIO.master()
  })

  val idle :: refilling :: writing :: invalidating :: reseting :: Nil = Enum(5)
  val state = RegInit(reseting)
  val resetIndex = RegInit((nSets-1).U(cacheConfig.indexLen.W))

  val validBanks =
    for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, Bool()))

  val tagBanks =
    for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, UInt(cacheConfig.tagLen.W)))

  val instrBanks =
    for (i <- 0 until nWays)
      yield for (j <- 0 until nBanks) yield Module(new TypedDualPortRam(nSets, UInt(dataWidth.W)))

  val lruUnit    = Module(new PLRU(nSets, nWays))
  val refillUnit = Module(new RefillUnit(AXIID = 0.U))
  val itlb       = Module(new ITLB())
  itlb.io.req.valid    := io.core.req.valid
  itlb.io.req.bits.vpn := io.core.req.bits.addr(31, 12)
  itlb.io.flush        := io.core.flush
  itlb.io.fence        := io.core.flush
  itlb.io.resp.ready   := io.core.req.ready

  io.tlb.instrReq := itlb.io.tlbInstrReq
  itlb.io.tlbInstrResp := io.tlb.instrResp

  val s0Valid = io.core.req.fire && !io.core.flush
  val s0Addr  = io.core.req.bits.addr

  val s0Tag   = itlb.io.resp.bits.pfn

  val vTag   = Wire(Vec(nWays, UInt(cacheConfig.tagLen.W)))

  val s0TagHit = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    s0TagHit(i) := validBanks(i).io.douta && vTag(i) === s0Tag
  }

  val s0HitWay     = OHToUInt(s0TagHit)

  val s1Valid    = RegInit(0.B)
  val s1Inv      = RegInit(0.B)
  val s1Addr     = RegInit(0.U(addrWidth.W))
  val s1HitWay   = RegInit(0.U(log2Ceil(nWays).W))
  val s1TlbResp  = RegInit(0.U.asTypeOf(new ITLBResp))
  val s1HitInBanks = RegInit(0.B)
  val s1Stall    = WireDefault(0.B)
  val s1UnCache  = RegInit(0.B)

  val s1InvComplete = RegInit(0.B)

  io.inv.req.ready := !io.core.req.valid && !s1Valid
  val reqInv = io.inv.req.fire

  io.inv.resp := s1InvComplete

  when (!s1Stall) {
    s1Valid    := s0Valid || reqInv
    s1Inv      := reqInv
    s1Addr     := Mux(reqInv, io.inv.req.bits, s0Addr)
    s1HitWay   := s0HitWay
    s1TlbResp  := itlb.io.resp.bits
    s1HitInBanks := s0TagHit.reduce(_ || _)
    s1UnCache  := itlb.io.resp.bits.uncache
  }

  when (!s1Stall) {
    s1InvComplete := 0.B
  } .elsewhen(state === invalidating) {
    s1InvComplete := 1.B
  }

  val pipeFlush   = RegInit(0.B) // delay flush for output
  val flushOutput = pipeFlush || io.core.flush

  val s1TlbMiss = !s1TlbResp.valid

  val s1Tag       = s1TlbResp.pfn
  val s1Index     = config.getIndex(s1Addr)
  val s1BankIndex = config.getBankIndex(s1Addr)

  val iBanks = Wire(Vec(nWays, Vec(nBanks, UInt(dataWidth.W))))
  for (i <- 0 until nWays) vTag(i) := tagBanks(i).io.douta
  for (i <- 0 until nWays)
    for (j <- 0 until nBanks)
      iBanks(i)(j) := instrBanks(i)(j).io.douta

  val lastRefillAddrValid  = RegInit(0.B)

  // val s1ReqBankIndex = Mux(s1UnCache, s1BankIndex, 0.U(config.bankIndexLen.W))
  val s1ReqPhyAddr = Cat(s1Tag, s1Index, 0.U(config.bankIndexLen.W), 0.U(config.bankOffsetLen.W))

  val s1HitInRefillBuffer = lastRefillAddrValid

  val hitData = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val hitDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    hitData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), iBanks(s1HitWay)(s1BankIndex + i.U), 0.U)
    hitDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  val hit          = s1Valid && s1HitInBanks
  val hitOrTlbMiss = s1Valid && (s1HitInBanks || s1TlbMiss)
  lruUnit.io.update.valid := (!s1Stall && s1Valid && !s1UnCache && !s1Inv)
  lruUnit.io.update.bits.index := s1Index
  val miss = s1Valid && !(s1HitInBanks || s1HitInRefillBuffer || s1TlbMiss || s1InvComplete)

  // 第一个周期发起 refill 信号
  refillUnit.io.addr.valid := (state === idle) && miss && !s1Inv
  refillUnit.io.addr.bits  := s1ReqPhyAddr
  refillUnit.io.uncache    := s1UnCache

  val writeToPipling = io.core.resp.ready || flushOutput
  refillUnit.io.data.ready := writeToPipling && (state === writing)

  switch(state) {
    is(idle) {
      when(miss && !s1Inv) {
        state          := refilling
      }.elsewhen (s1Inv && !s1InvComplete) {
        state          := invalidating
      }
    }
    is(refilling) {
      when(refillUnit.io.data.valid) {
        state                := writing
        lastRefillAddrValid  := 1.B
      }
    }
    is(writing) {
      when (writeToPipling) {
        state := idle
      }
    }
    is(invalidating) {
      state := idle
    }
    is(reseting) {
      when (resetIndex === 0.U) {
        state := idle
      } .otherwise {
        resetIndex := resetIndex - 1.U
      }
    }
  }
  when (state === idle && lastRefillAddrValid) {
    lastRefillAddrValid := 0.B
  }

  val refillWay = lruUnit.io.getLRU.way

  io.core.req.ready := (state === idle && !miss) && itlb.io.resp.valid
  lruUnit.io.update.bits.way := Mux(s1HitInRefillBuffer, refillWay ,s1HitWay)

  s1Stall := !(state === idle) || (miss && !s1Inv) || (s1Inv && !s1InvComplete)

  lruUnit.io.getLRU.valid := 1.B
  lruUnit.io.getLRU.index := s1Index

  for (i <- 0 until nWays) {
    tagBanks(i).io.wea   := 0.B
    tagBanks(i).io.addra := config.getIndex(s0Addr)
    tagBanks(i).io.dina  := 0.U
    validBanks(i).io.wea := 0.B
    validBanks(i).io.addra := config.getIndex(s0Addr)
    validBanks(i).io.dina := 0.B

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.wea   := 0.B
      instrBanks(i)(j).io.addra := config.getIndex(s0Addr)
      instrBanks(i)(j).io.dina  := 0.U
    }
  }
  for (i <- 0 until nWays) {
    tagBanks(i).io.web :=
      (refillUnit.io.data.fire && (i.U === refillWay) && !s1UnCache)
    tagBanks(i).io.addrb := s1Index
    tagBanks(i).io.dinb  := s1Tag

//    val validsNeedUpd = (refillUnit.io.data.fire && (i.U === refillWay) && !s1UnCache) || (state === invalidating)
//    when (validsNeedUpd) {
//      valids(i)(s1Index) := !s1Inv
//    }
    validBanks(i).io.web :=
      (refillUnit.io.data.fire && (i.U === refillWay) && !s1UnCache) ||
        (state === invalidating) ||
        (state === reseting)
    validBanks(i).io.addrb := Mux(state === reseting, resetIndex, s1Index)
    validBanks(i).io.dinb  := !s1Inv && (state =/= reseting)

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.web   := refillUnit.io.data.fire && (i.U === refillWay) && !s1UnCache
      instrBanks(i)(j).io.addrb := s1Index
      instrBanks(i)(j).io.dinb  := refillUnit.io.data.bits(j)
    }
  }

  val missData = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val missDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    missData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), refillUnit.io.data.bits(s1BankIndex+i.U), 0.U)
    missDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  io.core.resp.valid := !s1Inv && ((state === idle && hitOrTlbMiss) || (state === writing)) && !flushOutput
  io.core.resp.bits.data := MuxCase(
    VecInit(Seq.fill(fetchGroupSize+2)(0.U(32.W))),
    Seq(
      (state === writing)           -> missData,
      (state === idle && s1TlbMiss) -> 0.U.asTypeOf(Vec(fetchGroupSize+2, UInt(32.W))),
      (state === idle && hit)       -> hitData
    )
  )
  io.core.resp.bits.valid := MuxCase(
    VecInit(Seq.fill(fetchGroupSize + 2)(0.B)),
    Seq(
      (state === writing)              -> missDataValid,
      (state === idle && hitOrTlbMiss) -> hitDataValid
    )
  )
  io.core.resp.bits.except := s1TlbResp.instrEx

  io.axi <> refillUnit.io.axi

  when (!pipeFlush && io.core.flush && s1Stall) {
    pipeFlush := 1.B
  }
  when (!s1Stall) {
    pipeFlush := 0.B
  }
}

class ITLBv2 extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Valid(new ITLBReq))
    val resp = Decoupled(new ITLBResp)

    val tlbInstrReq = Output(UInt(20.W))
    val tlbInstrResp = Flipped(new TLBInstrResp)

    //
    val fence = Input(Bool())
    val flush = Input(Bool())
  })

  val idle :: refilling :: informing :: Nil = Enum(3)
  val state = RegInit(idle)

  val vpn   = RegInit(0.U(20.W))
  val pfn   = RegInit(0.U(20.W))
  val valid = RegInit(0.B)
  // temp data
  val refillReq = RegInit(0.U(20.W))
  val instrEx   = RegInit(0.U.asTypeOf(new TLBInstrEx))

  // state === idle
  val mapped = (!io.req.bits.vpn(19)) || (io.req.bits.vpn(19, 18) === "b11".U(2.W))
  val hit    = (io.req.bits.vpn === vpn) && valid
  // state === refilling
  io.tlbInstrReq := refillReq

  when (io.fence) {
    valid   := 0.B
  } .elsewhen(state === refilling) {
    vpn     := refillReq
    pfn     := io.tlbInstrResp.pfn
    valid   := io.tlbInstrResp.valid
  }

  // reforming
  // notice: flush 周期的请求信号是有效的 ！！！
  io.resp.valid        := ((!mapped || hit) && (state === idle)) || ((state === informing) && !io.flush)
  io.resp.bits.pfn     := Mux(mapped, pfn, Cat(0.U(3.W), io.req.bits.vpn(16, 0)))
  io.resp.bits.instrEx := instrEx
  io.resp.bits.valid   := (state === idle) || (!instrEx.invalid && !instrEx.refill)
  io.resp.bits.uncache := 0.B

  switch (state) {
    is(idle) {
      when (io.req.valid && mapped && !hit) {
        state     := refilling
        refillReq := io.req.bits.vpn
      }
    }
    is (refilling) {
      when (io.flush) {
        state   := idle
        instrEx := 0.U.asTypeOf(new TLBInstrEx)
      } .otherwise {
        state   := informing
        instrEx := io.tlbInstrResp.except
      }
    }
    is (informing) {
      when (io.resp.ready || io.flush) {
        state   := idle
        instrEx := 0.U.asTypeOf(new TLBInstrEx)
      }
    }
  }
}

class InstrCachev2(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays           = config.numOfWays
  val nSets           = config.numOfSets
  val nBanks          = config.numOfBanks

  val io = IO(new Bundle {
    val core = new ICacheWithCore1
    val tlb  = Flipped(new TLBWithICache)
    val inv  = new CacheInvalidate
    val axi  = AXIIO.master()
  })

  val idle :: refilling :: writing :: invalidating :: reseting :: Nil = Enum(5)
  val state = RegInit(reseting)
  val resetIndex = RegInit((nSets-1).U(cacheConfig.indexLen.W))

  val tagBanks =
    for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, UInt(cacheConfig.tagLen.W)))

  val validBanks =
    for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, Bool()))

  val instrBanks =
    for (i <- 0 until nWays)
      yield for (j <- 0 until nBanks) yield Module(new TypedDualPortRam(nSets, UInt(dataWidth.W)))

  val lruUnit    = Module(new PLRU(nSets, nWays))
  val refillUnit = Module(new RefillUnit(AXIID = 0.U))

  io.tlb.instrReq    := io.core.req.bits.addr(31, 12)

  val s0Valid = io.core.req.fire && !io.core.flush
  val s0Addr  = io.core.req.bits.addr

  for (i <- 0 until nWays) {
    tagBanks(i).io.wea := 0.B
    tagBanks(i).io.addra := config.getIndex(s0Addr)
    tagBanks(i).io.dina := 0.U
    validBanks(i).io.wea := 0.B
    validBanks(i).io.addra := config.getIndex(s0Addr)
    validBanks(i).io.dina := 0.B

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.wea := 0.B
      instrBanks(i)(j).io.addra := config.getIndex(s0Addr)
      instrBanks(i)(j).io.dina := 0.U
    }
  }


  val vTag   = Wire(Vec(nWays, UInt(cacheConfig.tagLen.W)))

  val s0MatTags   = Wire(Vec(nWays, Bool()))
  val s0MatValids = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    s0MatTags(i)   := vTag(i) === io.tlb.instrResp.pfn
    s0MatValids(i) := validBanks(i).io.douta
  }

  val s1Valid    = RegInit(0.B)
  val s1Inv      = RegInit(0.B)
  val s1Addr     = RegInit(0.U(addrWidth.W))
  val s1TlbResp  = RegInit(0.U.asTypeOf(new TLBInstrResp))
  val s1MatTags = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))
  val s1MatValids = RegInit(0.U.asTypeOf(Vec(nWays, Bool())))

  val s1InvComplete = RegInit(0.B)

  val s1Stall      = WireDefault(0.B)
  val s1HitInBanks = WireInit(0.B)
  val s1HitWay     = WireInit(0.U(log2Ceil(nWays).W))

  io.inv.req.ready := !io.core.req.valid && !s1Valid
  val reqInv = io.inv.req.fire

  io.inv.resp := s1InvComplete

  when (!s1Stall) {
    s1Valid      := s0Valid || reqInv
    s1Inv        := reqInv
    s1Addr       := Mux(reqInv, io.inv.req.bits, s0Addr)
    s1MatTags    := s0MatTags
    s1MatValids  := s0MatValids
    s1TlbResp    := io.tlb.instrResp
  }

  when (!s1Stall) {
    s1InvComplete := 0.B
  } .elsewhen(state === invalidating) {
    s1InvComplete := 1.B
  }

  val s1HitValids = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    s1HitValids(i) := s1MatValids(i) && s1MatTags(i)
  }
  s1HitInBanks := s1HitValids.reduce(_ || _)
  s1HitWay     := OHToUInt(s1HitValids)

  val s1TlbMiss = !s1TlbResp.valid

  val s1Tag       = s1TlbResp.pfn
  val s1Index     = config.getIndex(s1Addr)
  val s1BankIndex = config.getBankIndex(s1Addr) & config.getBankMask()

  val iBanks = Wire(Vec(nWays, Vec(nBanks, UInt(dataWidth.W))))
  for (i <- 0 until nWays) vTag(i) := tagBanks(i).io.douta
  for (i <- 0 until nWays)
    for (j <- 0 until nBanks)
      iBanks(i)(j) := instrBanks(i)(j).io.douta

  val lastRefillAddrValid  = RegInit(0.B)

  val s1PhyAddr = Cat(s1Tag, s1Index, 0.U(config.bankIndexLen.W), 0.U(config.bankOffsetLen.W))

  val s1HitInRefillBuffer = lastRefillAddrValid

  val hitData = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val hitDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    hitData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), iBanks(s1HitWay)(s1BankIndex + i.U), 0.U)
    hitDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  val hit          = s1Valid && s1HitInBanks
  val hitOrTlbMiss = s1Valid && (s1HitInBanks || s1TlbMiss)
  lruUnit.io.update.valid := (!s1Stall && s1Valid && !s1Inv)
  lruUnit.io.update.bits.index := s1Index
  val miss = s1Valid && !(s1HitInBanks || s1HitInRefillBuffer || s1TlbMiss || s1InvComplete)

  // val missAddrBuffer = Reg(UInt(32.W))


  // 第一个周期发起 refill 信号
  refillUnit.io.addr.valid := (state === idle) && miss && !s1Inv
  refillUnit.io.addr.bits  := s1PhyAddr
  refillUnit.io.uncache    := 0.B

  refillUnit.io.data.ready := (state === writing)

  switch(state) {
    is(idle) {
      when(miss && !s1Inv) {
        state          := refilling
        // missAddrBuffer := s1PhyAddr
      }.elsewhen (s1Inv && !s1InvComplete) {
        state          := invalidating
      }
    }
    is(refilling) {
      when(refillUnit.io.data.valid) {
        state                := writing
        lastRefillAddrValid  := 1.B
      }
    }
    is(writing) {
      state := idle
    }
    is(invalidating) {
      state := idle
    }
    is(reseting) {
      when(resetIndex === 0.U) {
        state := idle
      }.otherwise {
        resetIndex := resetIndex - 1.U
      }
    }
  }
  when (state === idle && lastRefillAddrValid) {
    lastRefillAddrValid := 0.B
  }

  val refillWay = lruUnit.io.getLRU.way

  io.core.req.ready := (state === idle && !miss)
  lruUnit.io.update.bits.way := Mux(state === writing, refillWay ,s1HitWay)

  s1Stall := !(state === idle) || (miss && !s1Inv) || (s1Inv && !s1InvComplete)

  lruUnit.io.getLRU.valid := 1.B
  lruUnit.io.getLRU.index := config.getIndex(s1PhyAddr)
  for (i <- 0 until nWays) {
    tagBanks(i).io.web :=
      (refillUnit.io.data.fire && (i.U === refillWay))
    tagBanks(i).io.addrb := s1Index
    tagBanks(i).io.dinb := s1Tag

    validBanks(i).io.web :=
      (refillUnit.io.data.fire && (i.U === refillWay)) ||
        (state === invalidating) ||
        (state === reseting)
    validBanks(i).io.addrb := Mux(state === reseting, resetIndex, s1Index)
    validBanks(i).io.dinb := !s1Inv && (state =/= reseting)

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.web   := refillUnit.io.data.fire && (i.U === refillWay)
      instrBanks(i)(j).io.addrb := config.getIndex(s1PhyAddr)
      instrBanks(i)(j).io.dinb  := refillUnit.io.data.bits(j)
    }
  }

  val refillData      = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val refillDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    refillData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), refillUnit.io.data.bits(s1BankIndex+i.U), 0.U)
    refillDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  val missData      = RegInit(0.U.asTypeOf(Vec(fetchGroupSize+2, UInt(dataWidth.W))))
  val missDataValid = RegInit(0.U.asTypeOf(Vec(fetchGroupSize+2, Bool())))

  when (state === writing) {
    missData      := refillData
    missDataValid := refillDataValid
  }

  io.core.resp.valid := 1.B
  io.core.resp.bits.data := MuxCase(
    VecInit(Seq.fill(fetchGroupSize+2)(0.U(32.W))),
    Seq(
      s1HitInRefillBuffer -> missData,
      s1TlbMiss           -> 0.U.asTypeOf(Vec(fetchGroupSize+2, UInt(32.W))),
      hit                 -> hitData
    )
  )
  io.core.resp.bits.valid := MuxCase(
    VecInit(Seq.fill(fetchGroupSize + 2)(0.B)),
    Seq(
      s1HitInRefillBuffer -> missDataValid,
      hitOrTlbMiss        -> hitDataValid
    )
  )
  io.core.resp.bits.except := s1TlbResp.except

  io.axi <> refillUnit.io.axi
}

class InstrCachev3(cacheConfig: CacheConfig) extends Module {
  implicit val config = cacheConfig
  val nWays           = config.numOfWays
  val nSets           = config.numOfSets
  val nBanks          = config.numOfBanks

  val io = IO(new Bundle {
    val core = new ICacheWithCore1
    val tlb  = Flipped(new TLBWithICache)
    val inv  = new CacheInvalidate
    val axi  = AXIIO.master()
  })

  val idle :: refilling :: writing :: invalidating :: Nil = Enum(4)
  val state = RegInit(idle)

  val tagValid =
    for (i <- 0 until nWays) yield Module(new TypedDualPortAsyncRam(nSets, new TagValidBundle))

  val instrBanks =
    for (i <- 0 until nWays)
      yield for (j <- 0 until nBanks) yield Module(new TypedDualPortRam(nSets, UInt(dataWidth.W)))

  val lruUnit    = Module(new PLRU(nSets, nWays))
  val refillUnit = Module(new RefillUnit(AXIID = 0.U))
  val itlb       = Module(new ITLB())
  itlb.io.req.valid    := io.core.req.valid
  itlb.io.req.bits.vpn := io.core.req.bits.addr(31, 12)
  itlb.io.flush        := io.core.flush
  itlb.io.fence        := io.core.flush
  itlb.io.resp.ready   := io.core.req.ready

  io.tlb.instrReq := itlb.io.tlbInstrReq
  itlb.io.tlbInstrResp := io.tlb.instrResp

  val s0Valid = io.core.req.fire && !io.core.flush
  val s0Addr  = io.core.req.bits.addr

  val s0Tag   = itlb.io.resp.bits.pfn

  val vTag   = Wire(Vec(nWays, new TagValidBundle))

  val s0TagHit = Wire(Vec(nWays, Bool()))
  for (i <- 0 until nWays) {
    s0TagHit(i) := vTag(i).valid && vTag(i).tag === s0Tag
  }

  val s0HitWay     = OHToUInt(s0TagHit)

  val s1Valid    = RegInit(0.B)
  val s1Inv      = RegInit(0.B)
  val s1Addr     = RegInit(0.U(addrWidth.W))
  val s1HitWay   = RegInit(0.U(log2Ceil(nWays).W))
  val s1TlbResp  = RegInit(0.U.asTypeOf(new ITLBResp))
  val s1HitInBanks = RegInit(0.B)
  val s1Stall    = WireDefault(0.B)

  val s1InvComplete = RegInit(0.B)

  io.inv.req.ready := !io.core.req.valid && !s1Valid
  val reqInv = io.inv.req.fire

  io.inv.resp := s1InvComplete

  when (!s1Stall) {
    s1Valid    := s0Valid || reqInv
    s1Inv      := reqInv
    s1Addr     := Mux(reqInv, io.inv.req.bits, s0Addr)
    s1HitWay   := s0HitWay
    s1TlbResp  := itlb.io.resp.bits
    s1HitInBanks := s0TagHit.reduce(_ || _)
  }

  when (!s1Stall) {
    s1InvComplete := 0.B
  } .elsewhen(state === invalidating) {
    s1InvComplete := 1.B
  }

  val pipeFlush   = RegInit(0.B) // delay flush for output
  val flushOutput = pipeFlush || io.core.flush

  val s1TlbMiss = !s1TlbResp.valid

  val s1Tag       = s1TlbResp.pfn
  val s1Index     = config.getIndex(s1Addr)
  val s1BankIndex = config.getBankIndex(s1Addr) & config.getBankMaskv2()

  val iBanks = Wire(Vec(nWays, Vec(nBanks, UInt(dataWidth.W))))
  for (i <- 0 until nWays) vTag(i) := tagValid(i).io.douta
  for (i <- 0 until nWays)
    for (j <- 0 until nBanks)
      iBanks(i)(j) := instrBanks(i)(j).io.douta

  val lastRefillAddrValid  = RegInit(0.B)

  val s1PhyAddr = Cat(s1Tag, s1Index, 0.U(config.bankIndexLen.W), 0.U(config.bankOffsetLen.W))

  val s1HitInRefillBuffer = lastRefillAddrValid

  val hitData = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val hitDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    hitData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), iBanks(s1HitWay)(s1BankIndex + i.U), 0.U)
    hitDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  val hit          = s1Valid && s1HitInBanks
  val hitOrTlbMiss = s1Valid && (s1HitInBanks || s1TlbMiss)
  lruUnit.io.update.valid := (!s1Stall && s1Valid)
  lruUnit.io.update.bits.index := s1Index
  val miss = s1Valid && !(s1HitInBanks || s1HitInRefillBuffer || s1TlbMiss || s1InvComplete)

  // val missAddrBuffer = Reg(UInt(32.W))


  // 第一个周期发起 refill 信号
  refillUnit.io.addr.valid := miss && !s1Inv && state === idle && !io.core.flush
  refillUnit.io.addr.bits  := s1PhyAddr
  refillUnit.io.uncache    := 0.B

  val writeToPipling = io.core.resp.ready || flushOutput
  refillUnit.io.data.ready := writeToPipling && (state === writing)

  switch(state) {
    is(idle) {
      when(miss && !s1Inv && !io.core.flush) {
        state          := refilling
        // missAddrBuffer := s1PhyAddr
      }.elsewhen (s1Inv && !s1InvComplete) {
        state          := invalidating
      }
    }
    is(refilling) {
      when(refillUnit.io.data.valid) {
        state                := writing
        lastRefillAddrValid  := 1.B
      }
    }
    is(writing) {
      when (writeToPipling) {
        state := idle
      }
    }
    is(invalidating) {
      state := idle
    }
  }
  when (state === idle && lastRefillAddrValid) {
    lastRefillAddrValid := 0.B
  }

  val refillWay = lruUnit.io.getLRU.way

  io.core.req.ready := (state === idle && !miss) && itlb.io.resp.valid
  lruUnit.io.update.bits.way := Mux(state === writing, refillWay ,s1HitWay)

  s1Stall := !(state === idle) || (miss && !s1Inv && !io.core.flush) || (s1Inv && !s1InvComplete)

  lruUnit.io.getLRU.valid := 1.B
  lruUnit.io.getLRU.index := config.getIndex(s1PhyAddr)

  for (i <- 0 until nWays) {
    tagValid(i).io.wea   := 0.B
    tagValid(i).io.addra := config.getIndex(s0Addr)
    tagValid(i).io.dina  := 0.U.asTypeOf(new TagValidBundle)

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.wea   := 0.B
      instrBanks(i)(j).io.addra := config.getIndex(s0Addr)
      instrBanks(i)(j).io.dina  := 0.U
    }
  }
  for (i <- 0 until nWays) {
    tagValid(i).io.web :=
      (refillUnit.io.data.fire && (i.U === refillWay)) ||
        (state === invalidating)
    tagValid(i).io.addrb := config.getIndex(s1PhyAddr)
    tagValid(i).io.dinb  := Cat(config.getTag(s1PhyAddr), !s1Inv).asTypeOf(new TagValidBundle)

    for (j <- 0 until nBanks) {
      instrBanks(i)(j).io.web   := refillUnit.io.data.fire && (i.U === refillWay)
      instrBanks(i)(j).io.addrb := config.getIndex(s1PhyAddr)
      instrBanks(i)(j).io.dinb  := refillUnit.io.data.bits(j)
    }
  }

  val missData = Wire(Vec(fetchGroupSize+2, UInt(dataWidth.W)))
  val missDataValid = Wire(Vec(fetchGroupSize+2, Bool()))
  for (i <- 0 until fetchGroupSize+2) {
    missData(i)      := Mux((s1BankIndex < (cacheConfig.numOfBanks-i).U), refillUnit.io.data.bits(s1BankIndex+i.U), 0.U)
    missDataValid(i) := (s1BankIndex < (cacheConfig.numOfBanks-i).U)
  }

  io.core.resp.valid := !s1Inv && ((state === idle && hitOrTlbMiss) || (state === writing)) && !flushOutput
  io.core.resp.bits.data := MuxCase(
    VecInit(Seq.fill(fetchGroupSize+2)(0.U(32.W))),
    Seq(
      (state === writing)           -> missData,
      (state === idle && s1TlbMiss) -> 0.U.asTypeOf(Vec(fetchGroupSize+2, UInt(32.W))),
      (state === idle && hit)       -> hitData
    )
  )
  io.core.resp.bits.valid := MuxCase(
    VecInit(Seq.fill(fetchGroupSize + 2)(0.B)),
    Seq(
      (state === writing)              -> missDataValid,
      (state === idle && hitOrTlbMiss) -> hitDataValid
    )
  )
  io.core.resp.bits.except := s1TlbResp.instrEx

  io.axi <> refillUnit.io.axi

  when (!pipeFlush && io.core.flush && state === refilling) {
    pipeFlush := 1.B
  }
  when (pipeFlush && state === writing) {
    pipeFlush := 0.B
  }
}