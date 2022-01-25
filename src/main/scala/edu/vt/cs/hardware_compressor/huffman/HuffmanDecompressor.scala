package edu.vt.cs.hardware_compressor.huffman

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import chisel3._
import chisel3.util._

import huffmanDecompressor.huffmanDecompressor


// Note: This module uses push input and pull output to facilitate block-style
//  input and output, so one or more universal connectors may be necessary to
//  avoid deadlock and/or circular logic. See documentation for DecoupledStream.
class HuffmanDecompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Vec(params.parallelism, Flipped(DecoupledStream(
      params.decompressorCharsIn, UInt(params.compressedCharBits.W))))
    val out = DecoupledStream(params.decompressorCharsOut,
      UInt(params.characterBits.W))
  })
  
  val huffman = Module(new huffmanDecompressor(params.huffman))
  
  // generate a rising edge
  huffman.io.start := RegNext(true.B, false.B);
  
  
  //============================================================================
  // DECOMPRESSOR INPUT
  //============================================================================
  
  
  for(i <- 0 until params.parallelism) {
    
    // We do not know how many bits were consumed until one cycle later. So I
    // have to consume the maximum amount and buffer whatever the compressor
    // does not accept. This complicates the processing of ready-valid.
    
    // following is an incomplete/incorrect implementation
    // TODO
    // connect data
    huffman.io.dataIn(i).bits := io.in(i).bits.asUInt // TODO
    
    // assert valid when valid is at least i
    huffman.io.dataIn(i).valid := io.in(i).valid >= i.U
    
    io.in(i).finished := huffman.io.finished
  }
  
  
  //============================================================================
  // DECOMPRESSOR OUTPUT
  //============================================================================
  
  for(i <- 0 until params.parallelism) {
    io.out(i).bits(0) := huffman.io.dataOut(i).bits
    
    // make output valid as a chunk
    io.out(i).valid := Mux(huffman.io.dataOut.valid && !huffman.io.finished,
      1.U, 0.U)
    
    huffman.io.dataout.ready := io.out(i).ready =/= 0.U
    
    io.out(i).finished := huffman.io.finished
  }
}
