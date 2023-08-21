package mboom.core.backend.utils

import chisel3._
import chisel3.util._
import mboom.core.backend.decode._
import mboom.core.backend.lsu._
import mboom.core.components._
class AGU extends Module {
    val io = IO(new Bundle() {
        val prf = Flipped(new RegFileReadIO)
        val in = Flipped(DecoupledIO(new MicroLSUOp))
        val toLDQ = DecoupledIO(new LDQEntry)
        val toSTQ = DecoupledIO(new STQEntry)
    })
    val readIn = io.in
    io.prf.r1Addr := readIn.bits.baseOp.rsAddr
    io.prf.r2Addr := readIn.bits.baseOp.rtAddr
    val (rsData, rdData) = (io.prf.r1Data, io.prf.r2Data)
    // val imm = readIn.bits.immediate
    io.in.ready := io.toSTQ.fire || io.toLDQ.fire

    // val addr = rdData + imm
    // the output to the load queue
    io.toLDQ.valid := io.in.valid && io.in.bits.loadMode =/= LoadMode.disable
    io.toLDQ.bits.valid := 1.B
    // io.toLDQ.bits.addr := addr

    // the output to the store queue
    io.toSTQ.valid := io.in.valid && io.in.bits.storeMode =/= StoreMode.disable
    io.toSTQ.bits.valid := 1.B
    // io.toSTQ.bits.addr := addr
    io.toSTQ.bits.data := rsData
//    io.out.bits := readIn.bits
//    io.out.bits.baseOp.op2.op := op2
//    io.out.bits.baseOp.op1.op := op1
//    io.out.valid := io.in.valid
}
