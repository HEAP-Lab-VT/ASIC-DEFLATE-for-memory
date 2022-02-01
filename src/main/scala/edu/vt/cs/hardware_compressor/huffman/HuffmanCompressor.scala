package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.ArithmeticOps._
import edu.vt.cs.hardware_compressor.util.WidthOps._

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
    val out = Vec(params.channelCount, DecoupledStream(
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
    huffman.io.characterFrequencyInputs.compressionLimit.get :=
      huffman.io.characterFrequencyInputs.currentByteOut + io.in_counter.valid
  } otherwise {
    huffman.io.characterFrequencyInputs.compressionLimit.get :=
      params.maxCompressionLimit.U
  }
  
  
  //============================================================================
  // COMPRESSOR INPUT
  //============================================================================
  
  huffman.io.compressionInputs.foreach(_.dataIn(0) := DontCare)
  huffman.io.compressionInputs.foreach(_.valid := DontCare)
  val waymodulus = Reg(UInt(params.channelCount.idxBits.W))
  var ready = true.B
  io.in_compressor.ready := 0.U
  for(i <- 0 until params.channelCount) {
    val way = (waymodulus + i.U).div(params.channelCount)._2
    
    // connect data
    huffman.io.compressionInputs(way).dataIn(0) := io.in_compressor.bits(i)
    
    // assert valid when valid is at least i and all previous are ready
    huffman.io.compressionInputs(way).valid := io.in_compressor.valid > i.U &&
      ready
    
    when(ready && io.in_compressor.valid <= i.U) {
      waymodulus := way
    }
    
    ready &&= huffman.io.compressionInputs(way).ready
    when(ready) {
      io.in_compressor.ready := (i + 1).U
    }
    
    if(params.huffman.variableCompression)
    when(io.in_compressor.finished) {
      // stop by setting the compression limit to the current byte
      huffman.io.compressionInputs(way).compressionLimit.get :=
        huffman.io.compressionInputs(way).currentByteOut + io.in_compressor.valid
    } otherwise {
      // set the compression limit as high as possible
      huffman.io.compressionInputs(way).compressionLimit.get :=
        params.maxCompressionLimit.U
    }
  }

  when(ready && io.in_compressor.valid <= params.channelCount.U) {
    waymodulus := waymodulus
  }
  
  
  //============================================================================
  // COMPRESSOR OUTPUT
  //============================================================================
  
  io.out.zip(huffman.io.outputs).foreach{case (out, subout) =>
    
    // packed starting from most-significant bit (1234xxxx)
    Iterator.from(0)
      .map(_ * params.compressedCharBits)
      .sliding(2)
      .map(i => subout.dataOut(i(1) - 1, i(0)))
      .zip(out.data.reverse.iterator)
      .foreach{o => o._2 := o._1}
    
    // make output valid as a chunk
    out.valid := Mux(subout.valid && subout.ready, subout.dataLength, 0.U)
    
    subout.ready := out.ready >= subout.dataLength
    
    out.finished := huffman.io.finished
  }
}

object HuffmanCompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanCompressor(params), args)
}
