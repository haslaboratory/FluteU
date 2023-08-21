package mboom.core.backend.lsu

import chisel3._
import chisel3.util._
import mboom.cache.{DCacheReq, DCacheReq1, DCacheReqv2, DCacheResp, DCacheRespv2, InformMeta}
import mboom.core.backend.decode.MicroOp
import mboom.util.ValidBundle
import mboom.core.components.MuxStageReg
import mboom.config.CPUConfig._
import mboom.core.components.MuxStageRegMode
import mboom.core.backend.decode.LoadMode
import mboom.core.backend.decode.StoreMode
import mboom.core.backend.lsu.component.Sbuffer

class LSUWithDataCacheIO extends Bundle {
  val req    = DecoupledIO(new DCacheReqv2)
  val resp   = Flipped(Valid(new DCacheRespv2))
  val inform = Flipped(Valid(new InformMeta))
}

// TODO: simplify
class MemReq extends Bundle {

  /**
    * If load, [[data]] is retrieved from sb with mask [[valid]]
    */
  val brMask       = UInt((nBranchCount+1).W)
  val data         = UInt(32.W)
  val origInData   = UInt(32.W)
  val addr         = UInt(32.W)
  val mode         = UInt(LoadMode.width.W)
  val valid        = Vec(4, Bool())
  val robAddr      = UInt(robEntryNumWidth.W)
  val writeRegAddr = UInt(phyRegAddrWidth.W)
  val writeEnable  = Bool()

  val tlblRfl      = Bool()
  val tlblInv      = Bool()
  val tlbsRfl      = Bool()
  val tlbsInv      = Bool()
  val tlbsMod      = Bool()
}


object LSUUtils {
  def getLoadData(originData: Vec[UInt], loadMode: UInt, offset: UInt, replacedData: Vec[UInt]) = {
    assert(loadMode.getWidth == LoadMode.width)
    assert(offset.getWidth == 2)
    assert(replacedData.length == 4)
    assert(replacedData(0).getWidth == 8)
    val lwlData  = MuxLookup(
      key = offset,
      default = 0.U,
      mapping = Seq(
        "b11".U -> Cat(replacedData(3), replacedData(2), replacedData(1), replacedData(0)),
        "b10".U -> Cat(replacedData(2), replacedData(1), replacedData(0), originData(0)),
        "b01".U -> Cat(replacedData(1), replacedData(0), originData(1), originData(0)),
        "b00".U -> Cat(replacedData(0), originData(2), originData(1), originData(0))
      )
    )
    val lwrData = MuxLookup(
      key = offset,
      default = 0.U,
      mapping = Seq(
        "b11".U -> Cat(originData(3), originData(2), originData(1), replacedData(3)),
        "b10".U -> Cat(originData(3), originData(2), replacedData(3), replacedData(2)),
        "b01".U -> Cat(originData(3), replacedData(3), replacedData(2), replacedData(1)),
        "b00".U -> Cat(replacedData(3), replacedData(2), replacedData(1), replacedData(0)),
        )
    )
    val loadData = MuxLookup(
      key = loadMode,
      default = Cat(replacedData(3), replacedData(2), replacedData(1), replacedData(0)),
      mapping = Seq(
        LoadMode.byteS -> Cat(Fill(24, replacedData(offset)(7)), replacedData(offset)),
        LoadMode.byteU -> Cat(0.U(24.W), replacedData(offset)),
        LoadMode.halfS -> Cat(Fill(16, replacedData(offset + 1.U)(7)), replacedData(offset + 1.U), replacedData(offset)),
        LoadMode.halfU -> Cat(0.U(16.W), replacedData(offset + 1.U), replacedData(offset)),
        LoadMode.word  -> Cat(replacedData(3), replacedData(2), replacedData(1), replacedData(0)),
        LoadMode.lwr   -> lwrData,
        LoadMode.lwl   -> lwlData
      )
    )
//    when (loadMode === LoadMode.lwr) {
//      printf(p"The origin data ${Hexadecimal(Cat(originData(3), originData(2), originData(1), originData(0)))} The replace data ${Hexadecimal(Cat(replacedData(3), replacedData(2), replacedData(1), replacedData(0)))} The lwl data ${Hexadecimal(loadData)}\n")
//    }
    loadData
  }
}
