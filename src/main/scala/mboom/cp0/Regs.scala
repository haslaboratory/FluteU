package mboom.cp0

import chisel3._
import chisel3.util.log2Ceil
import mboom.config.CPUConfig._

abstract class CP0BaseReg {
  val reg: Data
  val addr: Int
  val sel: Int
}

class CP0BadVAddr extends CP0BaseReg {
  override val reg       = RegInit(0.U(dataWidth.W))
  override val addr: Int = 8
  override val sel: Int  = 0
}

object CP0BadVAddr {
  val addr = 8
  val sel  = 0
}

class CP0Count extends CP0BaseReg {
  override val reg       = RegInit(0.U(dataWidth.W))
  override val addr: Int = 9
  override val sel: Int  = 0
}

object CP0Count {
  val addr = 9
  val sel  = 0
}

class StatusBundle extends Bundle {
  val cu         = UInt(4.W)
  val rp         = Bool()
  val zero_31_23 = UInt(4.W)
  val bev        = Bool()
  val ts         = Bool()
  val zero_20_16 = UInt(5.W)
  val im         = Vec(8, Bool())
  val zero_7_5   = UInt(3.W)
  val um         = Bool()
  val zero_4     = Bool()
  val erl        = Bool()
  val exl        = Bool()
  val ie         = Bool()
}

class CP0Status extends CP0BaseReg {
  override val reg = RegInit({
    val bundle = WireInit(0.U.asTypeOf(new StatusBundle))
    bundle.bev := 1.B
    // bundle.erl := 1.B
    bundle
  })
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 12
  override val sel: Int  = 0
}

object CP0Status {
  val addr = 12
  val sel  = 0
}

class CauseBundle extends Bundle {
  val bd         = Bool()
  val ti         = Bool()
  val zero_29_24 = UInt(6.W)
  val iv         = Bool()
  val zero_22_16 = UInt(7.W)
  val ip         = Vec(8, Bool())
  val zero_7     = Bool()
  val excCode    = UInt(5.W)
  val zero_1_0   = UInt(2.W)
}

class CP0Cause extends CP0BaseReg {
  override val reg: CauseBundle = RegInit(0.U.asTypeOf(new CauseBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 13
  override val sel: Int  = 0
}

object CP0Cause {
  val addr = 13
  val sel  = 0
}

class CP0EPC extends CP0BaseReg {
  override val reg       = RegInit(0.U(dataWidth.W))
  override val addr: Int = 14
  override val sel: Int  = 0
}

object CP0EPC {
  val addr = 14
  val sel  = 0
}

class CP0Compare extends CP0BaseReg {
  override val reg       = RegInit(0.U(dataWidth.W))
  override val addr: Int = 11
  override val sel: Int  = 0
}

object CP0Compare {
  val addr = 11
  val sel  = 0
}

// TODO ：添加Index寄存器：用于在TLB（转换后备缓存）中查找页表项的索引。
//        EntryLo0和EntryLo1寄存器：用于存储虚拟地址到物理地址的映射信息。
//        EntryHi寄存器：用于存储虚拟地址的高位和ASID信息。

// Index寄存器
class IndexBundle extends Bundle {
  val p          = Bool()                                // TLB命中为0，否则为1
  val zero       = UInt((31 - configTlbWidth).W)  // 不用
  val index      = UInt(configTlbWidth.W)                    // 在第几项命中
}

class CP0Index extends CP0BaseReg {
  override val reg: IndexBundle = RegInit(0.U.asTypeOf(new IndexBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 0
  override val sel: Int  = 0
}

object CP0Index {
  val addr = 0
  val sel  = 0
}

// EntryLo0 EntryLo1寄存器
class EntryLoBundle extends Bundle {
  val zero_31_26 = UInt(6.W)    // 不用
  val pfn        = UInt(20.W)
  val cache      = UInt(3.W)    // cache属性
  val dirty      = Bool()    // 脏属性
  val valid      = Bool()    // 有效位
  val global     = Bool()    // 全局标志位
}

class CP0EntryLo0 extends CP0BaseReg {
  override val reg: EntryLoBundle = RegInit(0.U.asTypeOf(new EntryLoBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 2
  override val sel: Int  = 0
}

class CP0EntryLo1 extends CP0BaseReg {
  override val reg: EntryLoBundle = RegInit(0.U.asTypeOf(new EntryLoBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 3
  override val sel: Int  = 0
}

object CP0EntryLo0 {
  val addr = 2
  val sel  = 0
}

object CP0EntryLo1 {
  val addr = 3
  val sel  = 0
}
// EntryHi
class EntryHiBundle extends Bundle {
  val vpn        = UInt(19.W)
  val zero_12_8  = UInt(5.W)    // 不用
  val asid       = UInt(8.W)    // 进程标志位
}

class CP0EntryHi extends CP0BaseReg {
  override val reg: EntryHiBundle = RegInit(0.U.asTypeOf(new EntryHiBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 10
  override val sel: Int  = 0
}

object CP0EntryHi {
  val addr = 10
  val sel  = 0
}

class RandomBundle extends Bundle{
  val zero   = UInt((32 - configTlbWidth).W)
  val random = UInt(configTlbWidth.W)
}

class CP0Random extends CP0BaseReg {
  override val reg: RandomBundle = RegInit({
    val bundle = Wire(new RandomBundle)
    bundle.zero   := 0.U
    bundle.random := (tlbSize - 1).U
    bundle
  })
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 1
  override val sel: Int  = 0
}

object CP0Random {
  val addr = 1
  val sel  = 0
}

class PageMaskBundle extends Bundle {
  val zero_31_25 = UInt(7.W)
  val mask       = UInt(12.W)
  val zero_12_0  = UInt(13.W)
}

class CP0PageMask extends CP0BaseReg {
  override val reg: PageMaskBundle = RegInit(0.U.asTypeOf(new PageMaskBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 5
  override val sel: Int  = 0
}

class EBaseBundle extends Bundle {
  val upper      = UInt(2.W)
  val ebase      = UInt(18.W)
  val zero_11_10 = UInt(2.W)
  val CPUNum     = UInt(10.W)
}

class CP0EBase extends CP0BaseReg {
  override val reg: EBaseBundle = RegInit({
    val bundle = WireInit(0.U(32.W).asTypeOf(new EBaseBundle))
    bundle.upper      := "b10".U(2.W)
    bundle.zero_11_10 := 0.U(2.W)
    bundle.CPUNum     := 0.U
    bundle
  })
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 15
  override val sel: Int = 1
}
// read only
class CP0PRID extends CP0BaseReg {
  override val reg = RegInit("h00018000".U)
  override val addr: Int = 15
  override val sel: Int = 0
}

class ContextBundle extends Bundle {
  val pteBase  = UInt(9.W)
  val badVPN2  = UInt(19.W)
  val zero_3_0 = UInt(4.W)
}

class CP0Context extends CP0BaseReg {
  override val reg: ContextBundle = RegInit(0.U.asTypeOf(new ContextBundle))
  assert(reg.getWidth == dataWidth)
  override val addr: Int = 4
  override val sel: Int = 0
}

class Config0Bundle extends Bundle {
  val m    = Bool()
  val k23  = UInt(3.W)
  val ku   = UInt(3.W)
  val impl = UInt(9.W)
  val be   = Bool()
  val at   = UInt(2.W)
  val ar   = UInt(3.W)
  val mt   = UInt(3.W)
  val non  = UInt(3.W)
  val vi   = Bool()
  val k0   = UInt(3.W)
}

class CP0Config0 extends CP0BaseReg {
  override val addr: Int = 16
  override val reg = RegInit({
    val bundle = WireInit(0.U.asTypeOf(new Config0Bundle))
    bundle.m := true.B
    bundle.mt := 1.U
    bundle.k0 := 3.U
    bundle
  })
  override val sel: Int = 0
}


class Config1Bundle extends Bundle {
  val m       = Bool()
  val mmuSize = UInt(6.W)
  val is      = UInt(3.W)
  val il      = UInt(3.W)
  val ia      = UInt(3.W)
  val ds      = UInt(3.W)
  val dl      = UInt(3.W)
  val da      = UInt(3.W)
  val c2      = Bool()
  val md      = Bool()
  val pc      = Bool()
  val wr      = Bool()
  val ca      = Bool()
  val ep      = Bool()
  val fp      = Bool()
}
// read only
class CP0Config1 extends CP0BaseReg {
  override val addr: Int = 16
  override val sel:  Int = 1

  override val reg = RegInit({
    val bundle = WireInit(0.U.asTypeOf(new Config1Bundle))
    bundle.m       := false.B
    bundle.mmuSize := (tlbSize - 1).U
    bundle.is := {
      iCacheConfig.numOfSets match {
        case 64   => 0.U
        case 128  => 1.U
        case 256  => 2.U
        case 512  => 3.U
        case 1024 => 4.U
        case 2048 => 5.U
        case 4096 => 6.U
        case 32   => 7.U
      }
    }
    bundle.il := {
      iCacheConfig.numOfBanks * 4 match {
        case 4   => 1.U
        case 8   => 2.U
        case 16  => 3.U
        case 32  => 4.U
        case 64  => 5.U
        case 128 => 6.U
      }
    }
    bundle.ia := (iCacheConfig.numOfWays - 1).U
    bundle.ds := {
      dCacheConfig.numOfSets match {
        case 64   => 0.U
        case 128  => 1.U
        case 256  => 2.U
        case 512  => 3.U
        case 1024 => 4.U
        case 2048 => 5.U
        case 4096 => 6.U
        case 32   => 7.U
      }
    }
    bundle.dl := {
      dCacheConfig.numOfBanks * 4 match {
        case 4   => 1.U
        case 8   => 2.U
        case 16  => 3.U
        case 32  => 4.U
        case 64  => 5.U
        case 128 => 6.U
      }
    }
    bundle.da := (dCacheConfig.numOfWays - 1).U
    bundle.c2 := false.B
    bundle.md := false.B
    bundle.pc := false.B
    bundle.wr := false.B
    bundle.ca := false.B
    bundle.ep := false.B
    bundle.fp := false.B
    bundle
  })
}

class CP0Config2 extends CP0BaseReg {
  override val reg = RegInit(0.U(32.W))
  override val addr: Int = 16
  override val sel: Int = 2
}

class WiredBundle extends Bundle {
  private val tlbWidth = log2Ceil(tlbSize)
  val zero             = UInt((32 - tlbWidth).W)
  val wired            = UInt(tlbWidth.W)

}
class CP0Wired extends CP0BaseReg {
  override val addr: Int = 6
  override val sel: Int = 0

  override val reg = RegInit(0.U.asTypeOf(new WiredBundle))
}

class CP0TagHi extends CP0BaseReg {
  override val reg = RegInit(0.U(32.W))
  override val addr: Int = 28
  override val sel: Int = 0
}

class CP0TagLo extends CP0BaseReg {
  override val reg = RegInit(0.U(32.W))
  override val addr: Int = 29
  override val sel: Int = 0
}

class CP0ErrorPC extends CP0BaseReg {
  override val reg = RegInit(0.U(32.W))
  override val addr: Int = 30
  override val sel: Int = 0
}