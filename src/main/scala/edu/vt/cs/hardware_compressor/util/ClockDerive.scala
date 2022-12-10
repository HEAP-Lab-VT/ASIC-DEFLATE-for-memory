package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.util.HasBlackBoxInline

class ClockDerive(edge: String = "") extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle{
    val clock_in = Input(Clock())
    val sig = Input(Bool())
    val clock_out = Output(Clock())
  })
  
  setInline("ClockDerive.v",
  s"""
    module ClockDerive(
      input clock_in,
      input sig,
      output reg clock_out
    );
      always @(${edge} clock_in) begin
        clock_out = sig;
      end
    endmodule
  """)
}

object ClockDerive {
  def apply(clock_in: Clock, sig: Bool, edge: String = ""): Clock = {
    val cd = Module(new ClockDerive(edge))
    cd.io.clock_in := clock_in
    cd.io.sig := sig
    cd.io.clock_out
  }
}
