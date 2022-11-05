package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


class AccumulateReplay(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(RestartableDecoupledStream(8, UInt(8.W)))
    val out = RestartableDecoupledStream(8, UInt(8.W))
  })
  
  val mem = Mem(4096, UInt(8.W))
  val head = RegInit(UInt(12.W), 0.U)
  val tail = RegInit(UInt(12.W), 0.U)
  
  val isMarked = RegInit(Bool(), false.B)
  val mark = RegInit(UInt(12.W), 0.U)
  val cap = Mux(isMarked, mark, tail)
  
  val inbuf = Reg(Vec(8, UInt(8.W)))
  val inbufLen = RegInit(UInt(4.W), 0.U)
  val outbuf = Reg(Vec(8, UInt(8.W)))
  val outbufLen = RegInit(UInt(4.W), 0.U)
  
  // load from memory
  val unconsumed = Mux(io.out.ready < outbufLen, outbufLen - io.out.ready, 0.U)
  for(i <- 0 until 8) {
    val j = (i.U - head)(2,0)
    val m = mem(((head + i.U) & 0xff8.U) + i.U)
    val b = outbuf((j +& unconsumed) % 8.U)
    val s = outbuf(j)
    val o = j +& outbufLen < 8.U +& io.out.ready
    b := Mux(o, m, s)
  }
  head := head + ((cap - head)(11,0) min (8.U - unconsumed))
  
  // store to memory
  for(i <- 0 until 8) {
    val a = ((tail + i.U) & 0xff8.U) + i.U
    when((tail >= head) ^ (a >= tail) ^ (a >= head)) {
      mem(a) := inbuf((i.U - tail)(2,0))
    }
  }
  tail := tail + inbufLen
  
  io.out.data := outbuf
  io.out.valid := outbufLen
  io.out.last := isMarked && head === mark
  outbufLen := (unconsumed +& (cap - head)(11,0)) min 8.U
  when(io.out.restart) {
    isMarked := false.B
    head := mark // for good measure
    outbufLen := 0.U // for good measure
  }
  
  io.in.ready := (head - tail - 1.U)(11,0) min 8.U
  io.in.restart := false.B
  inbuf := io.in.data
  inbufLen := io.in.valid min io.in.ready
  when(!isMarked && io.in.last && io.in.valid === 0.U) {
    io.in.restart := true.B
    isMarked := true.B
    mark := tail + inbufLen
  }
}
