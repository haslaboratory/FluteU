package mboom.core.backend.lsu
import chisel3._
import chisel3.util._
import mboom.config.CPUConfig._
import mboom.core.backend.ExecFlush
import mboom.core.backend.alu.OpAwaken
import mboom.core.backend.decode._
import mboom.core.components._
import mboom.core.backend.utils._

//class LSUEntry extends Bundle {
//    val uop = new MicroLSUOp
//    val op1Awaken = new OpAwaken
//    val op2AWaken = new OpAwaken
//}
class LsuIssue(detectWidth: Int) extends Module {
  val io = IO(new Bundle {
    val detect = Input(Vec(detectWidth, Valid(new MicroLSUOp)))
    val opReady = Input(Vec(detectWidth, new IssueReady))

    val issue = Output(Valid(UInt(log2Ceil(detectWidth).W)))
    val out   = Decoupled(new MicroLSUOp)

    val stallReq = Input(Bool())
    val stallRobDiffer = Input(UInt(robEntryNumWidth.W))

    val flush = Input(new ExecFlush(nBranchCount))
  })

  val available = Wire(Vec(detectWidth, Bool()))

  val uops = VecInit(io.detect.map(_.bits))

  for (i <- 0 until detectWidth) {
    val op1Available = io.opReady(i).op1Rdy
    val op2Available = io.opReady(i).op2Rdy
    val opExtAvailable = io.opReady(i).opExtRdy

    val opAvailable = op1Available && op2Available && opExtAvailable

    var hasStoreBefore = 0.B
    for (j <- 0 until i) {
       hasStoreBefore = hasStoreBefore || (uops(j).storeMode =/= StoreMode.disable)
    }
    val storeBusy = (uops(i).storeMode =/= StoreMode.disable) && (i.U =/= 0.U)
    available(i) :=
      io.detect(i).valid &&
      opAvailable &&
      !hasStoreBefore &&
      !storeBusy
  }

  val issueStuck = Wire(Vec(detectWidth, Bool()))
  for (i <- 0 until detectWidth) {
    issueStuck(i) :=
      io.flush.brMissPred || io.stallReq
  }

  val canIssue = available

  val issue = PriorityEncoder(canIssue.asUInt)
  val issueValid = canIssue(issue) && !issueStuck(issue)

  val stage = Module(new StageRegv2(new MicroLSUOp))

  // datapath
  stage.io.in.bits  := uops(issue)
  stage.io.in.valid := issueValid

  io.out  <> stage.io.out
  stage.io.flush := io.flush

  // 双端decoupled信号生成
  io.issue.bits  := issue
  io.issue.valid := stage.io.in.ready && issueValid

}
