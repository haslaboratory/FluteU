package mboom.core.backend.rename

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._

/**
  *input: addr(phyRegAddrWith.W): 输入的是查询的物理寄存器的地址
  * 
  *output: busy：表示该物理寄存器是否还在流水中，没有退休(1 yes)
  */
//class BusyTableReadPort extends Bundle {
//  val addr = Input(UInt(phyRegAddrWidth.W))
//  val busy = Output(Bool()) // busy = 1.B means that phyReg is in pipeline computing
//}

class BusyTableQueryPort extends Bundle {
  val op1Addr = Input(UInt(phyRegAddrWidth.W))
  val op2Addr = Input(UInt(phyRegAddrWidth.W))
  val op1Busy = Output(Bool())
  val op2Busy = Output(Bool())
}

class BusyTableExtQueryPort extends Bundle {
  val extAddr = Input(UInt(phyRegAddrWidth.W))
  val extBusy = Output(Bool())
}

/**
  * 
  * busytable 相当于使用bitmap维护寄存器的使用情况。
  * @param nRead  查询的寄存器的数目
  * @param nCheckIn 每次在忙的寄存器状态
  * @param nCheckOut  每次已经退休的寄存器状态
  */
class BusyTable(nRead: Int, nExtRead: Int, nCheckIn: Int, nCheckOut: Int) extends Module {
  private val n = phyRegAmount

  val io = IO(new Bundle {
    val read = Vec(nRead, new BusyTableQueryPort)
    val extRead = Vec(nExtRead, new BusyTableExtQueryPort)

    val checkIn  = Input(Vec(nCheckIn, Valid(UInt(phyRegAddrWidth.W))))
    val checkOut = Input(Vec(nCheckOut, Valid(UInt(phyRegAddrWidth.W))))

    val flush = Input(Bool())
  })

  val busyTable = RegInit(VecInit(Seq.fill(phyRegAmount)(0.B)))
  for (i <- 0 until nRead) {
    val busy1 = (UIntToOH(io.read(i).op1Addr)(n - 1, 0) & busyTable.asUInt).orR
    io.read(i).op1Busy := busy1
    val busy2 = (UIntToOH(io.read(i).op2Addr)(n - 1, 0) & busyTable.asUInt).orR
    io.read(i).op2Busy := busy2
  }
  for (i <- 0 until nExtRead) {
    val busy = (UIntToOH(io.extRead(i).extAddr)(n - 1, 0) & busyTable.asUInt).orR
    io.extRead(i).extBusy := busy
  }

  // 使用reduce进行聚合的操作
  val checkIMask = io.checkIn.map(d => UIntToOH(d.bits)(n - 1, 0) & Fill(n, d.valid)).reduce(_ | _)
  val checkOMask = io.checkOut.map(d => UIntToOH(d.bits)(n - 1, 0) & Fill(n, d.valid)).reduce(_ | _)

  // 更新busytable的状态
  val nextTable = (busyTable.asUInt | checkIMask) & ~checkOMask // & 优先级大于 |

  when (io.flush) {
    busyTable := 0.U.asTypeOf(Vec(phyRegAmount, Bool()))
  } .otherwise {
    busyTable := nextTable.asBools
  }

}
