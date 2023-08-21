package mboom.core.backend.utils

import chisel3.{Bool, assert}
import mboom.core.backend.ExecFlush
import mboom.core.backend.decode.MicroBaseOp

object ExecuteUtil {
  def needFlush(flush: ExecFlush, op: MicroBaseOp): Bool = {
    flush.extFlush // || (flush.brMissPred && (flush.brMask & op.brMask).orR)
  }

}