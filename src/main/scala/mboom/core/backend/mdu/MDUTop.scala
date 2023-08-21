package mboom.core.backend.mdu

import chisel3._
import chisel3.util._
import mboom.cache.CacheInvalidate
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.{MicroMDUOp, MicroOp}
import mboom.core.backend.alu.AluWB
import mboom.core.backend.utils.IssueReady
import mboom.core.components.RegFileReadIO
import mboom.cp0.{CP0Read, CP0TLBReq, CP0Write}
import mboom.mmu.TLBReq
import mboom.config.CPUConfig._

class MDUTop extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MicroMDUOp))
    val wb = Output(new AluWB)

    val prf  = Flipped(new RegFileReadIO)
    val hlW  = Input(new HILOWrite)
    val cp0Read  = Flipped(new CP0Read)
    val cp0Write = Output(new CP0Write)

    val tlbReq    = Flipped(new TLBReq)
    val cp0TLBReq = ValidIO(new CP0TLBReq)

    val icacheInv = Flipped(new CacheInvalidate)
    val dcacheInv = Flipped(new CacheInvalidate)

    val flush  = Input(new ExecFlush(nBranchCount))
  })


  val mduRead   = Module(new MduRead)
  val mduExecute = Module(new MduExecute)

  mduRead.io.in    <> io.in

  mduExecute.io.in <> mduRead.io.out
  io.wb := mduExecute.io.wb

  mduRead.io.prf           <> io.prf
  mduExecute.io.cp0Read    <> io.cp0Read
  io.cp0Write              := mduExecute.io.cp0Write
  mduExecute.io.hiloCommit := io.hlW
  mduExecute.io.cp0TLBReq <> io.cp0TLBReq
  mduExecute.io.tlbReq    <> io.tlbReq
  mduExecute.io.dcacheInv <> io.dcacheInv
  mduExecute.io.icacheInv <> io.icacheInv

  // flush
  mduRead.io.flush   := io.flush
  mduExecute.io.flush := io.flush
}
