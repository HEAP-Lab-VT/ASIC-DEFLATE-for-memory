package edu.vt.cs.hardware_compressor.huffman

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import chisel3._
import chisel3.util._

import huffmanCompressor.huffmanCompressor


// Note: This module uses push input and pull output to facilitate block-style
//  input and output, so one or more universal connectors may be necessary to
//  avoid deadlock and/or circular logic. See documentation for DecoupledStream.
class HuffmanCompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in_counter = Flipped(DecoupledStream(params.counterCharsIn,
      UInt(params.characterBits.W)))
    val in_compressor = Flipped(DecoupledStream(params.compressorCharsIn,
      UInt(params.characterBits.W)))
    val out = Vec(params.compressorChannelsOut, DecoupledStream(
      params.compressorCharsOut, UInt(params.compressedCharBits.W)))
  })
  
  val huffman = Module(new huffmanCompressor(params.huffman))
  
  // generate a rising edge
  huffman.io.start := RegNext(true.B, false.B);
  
  
  //============================================================================
  // COUNTER INPUT
  //============================================================================
  
  // This register only stores the least significant bits of the index because
  // we assume that the index only advances by small amounts, and we only need
  // it to determine how many characters were consumed per cycle.
  // val counterIdx = RegInit(UInt(params.counterCharsIn.idxBits.W), 0.U)
  // counterIdx := huffman.io.characterFrequencyInputs.currentByteOut
  
  // Thankfully, data translates directly.
  huffman.io.characterFrequencyInputs.dataIn := io.in_counter.bits
  
  // only assert valid when all inputs are valid
  huffman.io.characterFrequencyInputs.valid :=
    io.in_counter.valid === params.counterCharsIn.U ||
    (io.in_counter.finished && io.in_counter.valid =/= 0.U)
  
  // We do not know soon enough how many bytes are consumed, so we make the
  // (rather liberal) assumption that when ready and valid are asserted, all
  // inputs are consumed.
  io.in_counter.ready := Mux(huffman.io.characterFrequencyInputs.ready &&
    huffman.io.characterFrequencyInputs.valid, params.counterCharsIn.U, 0.U)
  
  if(params.huffman.variableCompression)
  when(io.in_counter.finished) {
    huffman.io.characterFrequencyInputs.compressionLimit :=
      huffman.io.characterFrequencyInputs.currentByteOut + io.in_counter.valid
  }
  
  
  //============================================================================
  // COMPRESSOR INPUT
  //============================================================================
  
  var ready = true.B
  for(i <- 0 until params.compressionParallelism) {
    
    // connect data
    huffman.io.compressorInputs(i).dataIn := io.in_compressor.bits(i)
    
    // assert valid when valid is at least i and all previous are ready
    huffman.io.compressorInputs(i).valid := io.in_compressor.valid >= i.U &&
      ready
    
    ready &&= huffman.io.compressorInputs(i).ready
    when(ready) {
      io.in_counter.ready := (i + 1).U
    }
    
    when(io.in_compressor.finished) {
      // stop by setting the compression limit to the current byte
      // TODO: Figure out whether the compression limit includes other ways.
      huffman.io.compressorInputs(i).compressionLimit :=
        huffman.io.compressorInputs(i).currentByteOut +
        Mux(huffman.io.compressorInputs(i).valid, 1.U, 0.U)
    } otherwise {
      // set the compression limit as high as possible
      huffman.io.compressorInputs(i).compressionLimit := params.maxChars.U
    }
  }
  
  
  //============================================================================
  // COMPRESSOR OUTPUT
  //============================================================================
  
  io.out.zip(huffman.io.outputs).foreach{case (out, subout) =>
    
    // assume packed starting from the least-significant bit (xxxx4321)
    out.bits := subout.dataOut.asBools
    
    // make output valid as a chunk
    out.valid := Mux(subout.valid && subout.ready, subout.dataLength, 0.U)
    
    subout.ready := out.ready >= subout.dataLength
    
    out.finished := huffman.io.finished
  }
}
