package mboom.components

import chisel3._
import chisel3.util._
import mboom.config.CPUConfig
// wrapper class for single_port_ram
class SinglePortBRAM(DATA_WIDTH: Int, DEPTH: Int, LATENCY: Int = 1) extends BlackBox(Map(
  "DATA_WIDTH" -> DATA_WIDTH,
  "DEPTH" -> DEPTH,
  "LATENCY" -> LATENCY)) with HasBlackBoxInline {

  override val desiredName = "single_port_bram"

  val io = IO(new Bundle() {
    val clk  = Input(Clock())
    val rst  = Input(Reset())
    val we   = Input(Bool())
    val addr = Input(UInt(log2Ceil(DEPTH).W))
    val din  = Input(UInt(DATA_WIDTH.W))
    val dout = Output(UInt(DATA_WIDTH.W))
  })

  setInline(
    "single_port_bram.v",
    s"""
       |module single_port_bram # (
       |  parameter DATA_WIDTH = 32,
       |	parameter DEPTH      = 1024,
       |	parameter LATENCY    = 1
       |)(
       |	input  clk,
       |	input  rst,
       |	input  we,
       |	input  [$$clog2(DEPTH)-1:0] addr,
       |	input  [DATA_WIDTH-1:0]  din,
       |	output [DATA_WIDTH-1:0]  dout
       |);
       |
       |// xpm_memory_spram: Single Port RAM
       |// Xilinx Parameterized Macro, Version 2016.2
       |xpm_memory_spram #(
       |	// Common module parameters
       |	.MEMORY_SIZE(DATA_WIDTH * DEPTH),
       |	.MEMORY_PRIMITIVE("auto"),
       |	.USE_MEM_INIT(0),
       |	.WAKEUP_TIME("disable_sleep"),
       |	.MESSAGE_CONTROL(0),
       |	// Port A module parameters
       |	.WRITE_DATA_WIDTH_A(DATA_WIDTH),
       |	.READ_DATA_WIDTH_A(DATA_WIDTH),
       |	.READ_RESET_VALUE_A("0"),
       |	.READ_LATENCY_A(LATENCY),
       |	.WRITE_MODE_A("write_first")
       |) xpm_mem (
       |	// Common module ports
       |	.sleep          ( 1'b0  ),
       |	// Port A module ports
       |	.clka           ( clk   ),
       |	.rsta           ( rst   ),
       |	.ena            ( 1'b1  ),
       |	.regcea         ( 1'b0  ),
       |	.wea            ( we    ),
       |	.addra          ( addr  ),
       |	.dina           ( din   ),
       |	.injectsbiterra ( 1'b0  ), // do not change
       |	.injectdbiterra ( 1'b0  ), // do not change
       |	.douta          ( dout  ),
       |	.sbiterra       (       ), // do not change
       |	.dbiterra       (       )  // do not change
       |);
       |
       |endmodule
       |""".stripMargin
  )
}