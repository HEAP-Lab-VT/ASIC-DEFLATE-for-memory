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
  
  val cHuffman = Module(new huffmanCompressor(params.cHuffman))
  
  // generate a rising edge
  cHuffman.io.start := RegNext(true.B, false.B);
  
  
  //============================================================================
  // COUNTER INPUT
  //============================================================================
  
  // This register only stores the least significant bits of the index because
  // we assume that the index only advances by small amounts, and we only need
  // it to determine how many characters were consumed per cycle.
  // val counterIdx = RegInit(UInt(params.counterCharsIn.idxBits.W), 0.U)
  // counterIdx := cHuffman.io.characterFrequencyInputs.currentByteOut
  
  // Thankfully, data translates directly.
  cHuffman.io.characterFrequencyInputs.dataIn := io.in_counter.bits
  
  // only assert valid when all inputs are valid
  cHuffman.io.characterFrequencyInputs.valid :=
    io.in_counter.valid === params.counterCharsIn.U ||
    (io.in_counter.finished && io.in_counter.valid =/= 0.U)
  
  // We do not know soon enough how many bytes are consumed, so we make the
  // (rather liberal) assumption that when ready and valid are asserted, all
  // inputs are consumed.
  io.in_counter.ready := Mux(cHuffman.io.characterFrequencyInputs.ready &&
    cHuffman.io.characterFrequencyInputs.valid, params.counterCharsIn.U, 0.U)
  
  if(params.cHuffman.variableCompression)
  when(io.in_counter.finished) {
    cHuffman.io.characterFrequencyInputs.compressionLimit :=
      cHuffman.io.characterFrequencyInputs.currentByteOut + io.in_counter.valid
  }
  
  
  //============================================================================
  // COMPRESSOR INPUT
  //============================================================================
  // This is similar to counter input
  
  
  for(i <- 0 until params.compressionParallelism) {
    
    // connect data
    cHuffman.io.compressorInputs(i).dataIn := io.in_compressor.bits(i)
    
    // assert valid when valid is at least i
    cHuffman.io.compressorInputs(i) := io.in_compressor.valid >= i.U
    
    when(io.in_compressor.finished) {
      // stop by setting the compression limit to the current byte
      // TODO: Figure out whether the compression limit includes other ways.
      cHuffman.io.compressorInputs(i).compressionLimit :=
        cHuffman.io.compressorInputs(i).currentByteOut +
        Mux(cHuffman.io.compressorInputs(i).valid, 1.U, 0.U)
    } otherwise {
      // set the compression limit as high as possible
      cHuffman.io.compressorInputs(i).compressionLimit := params.maxChars.U
    }
  }
  
  io.in_counter.ready :=
    PriorityEncoder(cHuffman.io.compressorInputs.map(!_.ready) :+ true.B)
  
  
  //============================================================================
  // COMPRESSOR OUTPUT
  //============================================================================
  
  io.out.zip(cHuffman.io.outputs).foreach{case (out, subout) =>
    
    // assume packed starting from the least-significant bit (xxxx4321)
    out.bits := subout.dataOut.asBools
    
    // make output valid as a chunk
    out.valid := Mux(subout.valid && subout.ready, subout.dataLength, 0.U)
    
    subout.ready := out.ready >= subout.dataLength
    
    out.finished := cHuffman.io.finished
  }
}
