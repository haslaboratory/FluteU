package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush

class BusyTablev2(nRead: Int, nExtRead: Int, nCheckIn: Int, nCheckOut: Int, nBrCount: Int) extends Module {
  private val n = phyRegAmount

  val io = IO(new Bundle {
    val read     = Vec(nRead, new BusyTableQueryPort)
    val extRead  = Vec(nExtRead, new BusyTableExtQueryPort)

    val checkIn  = Input(Vec(nCheckIn, Valid(UInt(phyRegAddrWidth.W))))
    val checkOut = Input(Vec(nCheckOut, Valid(UInt(phyRegAddrWidth.W))))

    val flush    = Input(new RenameFlush(nBrCount))

    // branch
    val brRequest = Input(Bool())
    val brTag     = Input(UInt(log2Ceil(nBrCount).W))
  })

  val busyTable = RegInit(0.U(phyRegAmount.W))
  for (i <- 0 until nRead) {
    val busy1 = (UIntToOH(io.read(i).op1Addr)(n - 1, 0) & busyTable).orR
    io.read(i).op1Busy := busy1
    val busy2 = (UIntToOH(io.read(i).op2Addr)(n - 1, 0) & busyTable).orR
    io.read(i).op2Busy := busy2
  }
  for (i <- 0 until nExtRead) {
    val busy = (UIntToOH(io.extRead(i).extAddr)(n - 1, 0) & busyTable).orR
    io.extRead(i).extBusy := busy
  }

  // 使用reduce进行聚合的操作
  val checkIMask = io.checkIn.map(d => UIntToOH(d.bits)(n - 1, 0) & Fill(n, d.valid)).reduce(_ | _)
  val checkOMask = io.checkOut.map(d => UIntToOH(d.bits)(n - 1, 0) & Fill(n, d.valid)).reduce(_ | _)

  val cBusyTables = RegInit(0.U.asTypeOf(Vec(nBrCount, UInt(phyRegAmount.W))))

  // 更新busytable的状态
  val nextTable  = (busyTable | checkIMask) & (~checkOMask).asUInt // & 优先级大于 |
  val nextcTables = VecInit(cBusyTables.map(_ & (~checkOMask).asUInt))

  when(io.flush.extFlush) {
    busyTable := 0.U
  }.elsewhen(io.flush.brRestore) {
    busyTable := nextcTables(io.flush.brTag)
  }.otherwise {
    busyTable := nextTable
  }

  for (i <- 0 until nBrCount) {
    when (io.brRequest && io.brTag === i.U) {
      cBusyTables(i) := nextTable
    } .otherwise {
      cBusyTables(i) := nextcTables(i)
    }
  }
}
