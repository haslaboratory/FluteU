//package mboom.core.backend.alu
//
//import chisel3._
//import chisel3.util._
//import mboom.core.backend.decode._
//import mboom.core.backend.utils._
//class LRUEntry extends Bundle {
//  val num = UInt(6.W)
//  val idx = UInt(3.W)
//}
//class OpAwaken extends Bundle {
//  private val deqNum = 2 // ALU 流水线个数
//
//  val awaken = Bool()
//  val sel    = UInt(log2Ceil(deqNum).W)
//}
//class AluEntry extends Bundle {
//  // val forTest   = Bool()
//  val uop       = new MicroALUOp
//  val op1Awaken = new OpAwaken
//  val op2Awaken = new OpAwaken
//}
//object LRUEntry {
//  def apply(num: UInt, idx: UInt): LRUEntry = {
//    val e = WireInit(0.U.asTypeOf(new LRUEntry))
//    e.num := num
//    e.idx := idx
//    e
//  }
//}
//class IssueLRU(detectWidth: Int) extends Module {
//  assert(detectWidth == 8)
//  private val numOfAluPipeline = 2
//  val io = IO(new Bundle {
//    val req   = Output(UInt(log2Ceil(detectWidth).W))
//
//    val upd = Input(Vec(numOfAluPipeline, Valid(UInt(log2Ceil(detectWidth).W))))
//  })
//
//  val lruReg      = RegInit(VecInit(
//    for (i <- 0 until detectWidth) yield LRUEntry(0.U, i.U)
//  ))
//
//  for (i <- 0 until detectWidth) {
//    when ((i.U === io.upd(0).bits && io.upd(0).valid) || (i.U === io.upd(1).bits && io.upd(1).valid)) {
//      lruReg(i).num := 0.U
//    } .elsewhen(io.upd(0).valid || io.upd(1).valid) {
//      lruReg(i).num := lruReg(i).num + 1.U
//    }
//  }
//
//  val req = RegNext(lruReg.reduceTree((a, b) => Mux(a.num >= b.num, a, b)).idx, 0.U(log2Ceil(detectWidth).W))
//  io.req := req
//
//
//}
//
//class AluIssueRR(detectWidth: Int) extends Module {
//  private val numOfAluPipeline = 2
//
//  val io = IO(new Bundle {
//    // 接收窗口
//    val detect = Input(Vec(detectWidth, Valid(new MicroALUOp)))
//
////    val aluDetect = Input(Vec(detectWidth, Valid(new MicroALUOp(rename = true))))
//    // data from Issue Stage Reg
//    val wake = Input(Vec(numOfAluPipeline, Valid(new AluEntry)))
//
//    val bt = Vec(2 * detectWidth, Flipped(new BusyTableReadPort))
//
//    val issue = Output(Vec(detectWidth, Bool()))
//    val out   = Output(Vec(numOfAluPipeline, Valid(new AluEntry)))
//  })
//
//  val avalible = Wire(Vec(detectWidth, Bool()))
//  val awaken   = Wire(Vec(detectWidth, Bool()))
//
//  val op1Awaken = Wire(Vec(detectWidth, new OpAwaken))
//  val op2Awaken = Wire(Vec(detectWidth, new OpAwaken))
//
//  val uops = VecInit(io.detect.map(_.bits))
//
//  for (i <- 0 until detectWidth) {
//    // avalible 能够发射（包括唤醒的指令）
//    val bt = Wire(Vec(2, Bool()))
//
//    io.bt(i * 2).addr     := uops(i).baseOp.rsAddr
//    io.bt(i * 2 + 1).addr := uops(i).baseOp.rtAddr
//
//    bt(0) := io.bt(i * 2).busy
//    bt(1) := io.bt(i * 2 + 1).busy
//
//    // awaken
//    val op1AwakenByWho = Wire(Vec(numOfAluPipeline, Bool()))
//    val op2AwakenByWho = Wire(Vec(numOfAluPipeline, Bool()))
//    for (j <- 0 until numOfAluPipeline) {
//      val (op1, op2) = ExecuteUtil.awake(io.wake(j).bits.uop.baseOp, uops(i).baseOp)
//      op1AwakenByWho(j) := io.wake(j).valid && io.detect(i).valid && op1
//      op2AwakenByWho(j) := io.wake(j).valid && io.detect(i).valid && op2
//    }
//
//    op1Awaken(i).awaken := op1AwakenByWho.reduce(_ | _)
//    op1Awaken(i).sel    := OHToUInt(op1AwakenByWho)
//
//    op2Awaken(i).awaken := op2AwakenByWho.reduce(_ | _)
//    op2Awaken(i).sel    := OHToUInt(op2AwakenByWho)
//
//    // 计算 avalible 与 awaken
//    val op1Avalible = ExecuteUtil.op1Ready(uops(i).baseOp, bt) || op1Awaken(i).awaken
//    val op2Avalible = ExecuteUtil.op2Ready(uops(i).baseOp, bt) || op2Awaken(i).awaken
//
//    avalible(i) := op1Avalible && op2Avalible && io.detect(i).valid
//
//    awaken(i) := avalible(i) && (op1Awaken(i).awaken || op2Awaken(i).awaken) // awaken 是 avalible的子集
//  }
//
//  // select 可优化为优先发送唤醒的指令，这里简单处理
//  val canIssue = avalible
//
////  val lruUnit    = Module(new IssueLRU(detectWidth))
////  val issueIndex = lruUnit.io.req
//  val issueIndex = RegInit(0.U)
//
//  val rotateCanIssue = AluIssueUtil.rotateWindow(detectWidth, canIssue, issueIndex)
//  val rotateIssue    = AluIssueUtil.selectFirstN(rotateCanIssue.asUInt, numOfAluPipeline)
//  val issue          = VecInit(rotateIssue.map(a => a + issueIndex))
//
//  val issueV = WireInit(VecInit(issue.map({ case a => canIssue(a) })))
//
//  when(issue(0) === issue(1)) {
//    issueV(1) := 0.B
//  }
//
//  for (i <- 0 until detectWidth) {
//    io.issue(i) := ((i.U === issue(0)) && issueV(0)) || ((i.U === issue(1)) && issueV(1))
//  }
//  for (i <- 0 until numOfAluPipeline) {
//
//    io.out(i).bits.uop       := uops(issue(i))
//    io.out(i).bits.op1Awaken := op1Awaken(issue(i))
//    io.out(i).bits.op2Awaken := op2Awaken(issue(i))
//    io.out(i).valid          := issueV(i)
//  }
//
//  when (issueV(0) || issueV(1)) {
//    issueIndex := issueIndex + 2.U
//  }
////  for (i <- 0 until numOfAluPipeline) {
////    lruUnit.io.upd(i).valid := issueV(i)
////    lruUnit.io.upd(i).bits  := issue(i)
////  }
//}
//
//object AluIssueUtil {
//  def selectFirstN(in: UInt, n: Int) = {
//    assert(n == 2)
//    val sels = Wire(Vec(n, UInt(log2Ceil(in.getWidth).W)))
//    sels(0) := PriorityEncoder(in)
//    val mask = in - (1.U << sels(0)).asUInt
//    sels(1) := PriorityEncoder(mask)
//    sels
//  }
//
//  def rotateWindow(width: Int, in: Vec[Bool], index: UInt) = {
//    val out = WireInit(0.U.asTypeOf(Vec(width, Bool())))
//    for (i <- 0 until width) {
//      out(i) := in(i.U(width.W) + index)
//    }
//    out
//  }
//}