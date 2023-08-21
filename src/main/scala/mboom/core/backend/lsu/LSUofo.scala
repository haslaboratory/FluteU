package mboom.core.backend.lsu
import chisel3._
import chisel3.util._
import mboom.cache.{DCacheReq, DCacheResp}
import mboom.core.backend.decode.{LoadMode, MicroLSUOp, StoreMode}
import mboom.util.ValidBundle
import mboom.core.components.{MuxStageReg, MuxStageRegMode}
import mboom.core.backend.lsu.component.{Sbuffer, SbufferEntry, WriteBackBuffer}
import mboom.config.CPUConfig._

class LDQEntry extends Bundle {
  val valid = Bool(); // the valid of the ldq entry
  val addr = UInt(instrWidth.W)
  val executed  = Bool() // load sent to the memory
  val succeed = Bool() // success
}

class STQEntry extends Bundle {
  val valid = Bool()
  val addr = UInt(instrWidth.W)
  val committed = Bool() // the instr committed
  val succeed = Bool()  // success
  val data = UInt(dataWidth.W)
}

class LSUofo extends Module {
    val io = IO(new Bundle() {
        val dcache = new LSUWithDataCacheIO
    })

}


