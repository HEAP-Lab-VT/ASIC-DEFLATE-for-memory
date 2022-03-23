package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


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
  
  val huffman = Module(new huffmanCompressor.huffmanCompressor(params.huffman))
  
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
  // Chandler's module requires that valid is asserted at EOF because the
  // frequency counter can only change to the finished state when valid is
  // asserted.
  huffman.io.characterFrequencyInputs.valid :=
    io.in_counter.valid === params.counterCharsIn.U ||
    (io.in_counter.finished /*&& io.in_counter.valid =/= 0.U*/)
  
  // We do not know soon enough how many bytes are consumed, so we make the
  // (rather liberal) assumption that when ready and valid are asserted, all
  // inputs are consumed.
  // TODO: assert ready when frequency counting is finished
  io.in_counter.ready := Mux(huffman.io.characterFrequencyInputs.ready &&
    huffman.io.characterFrequencyInputs.valid, params.counterCharsIn.U, 0.U)
  
  if(params.huffman.variableCompression)
  when(io.in_counter.finished) {
    huffman.io.characterFrequencyInputs.compressionLimit.get :=
      huffman.io.characterFrequencyInputs.currentByteOut +& io.in_counter.valid
  } otherwise {
    huffman.io.characterFrequencyInputs.compressionLimit.get :=
      params.maxCompressionLimit.U
  }
  
  
  //============================================================================
  // COMPRESSOR INPUT
  //============================================================================
  
  // make a DontCare fallback to please firrtl
  huffman.io.compressionInputs.foreach{input =>
    input.dataIn(0) := DontCare
    input.valid := DontCare
    if(params.huffman.variableCompression)
    input.compressionLimit.get := params.maxCompressionLimit.U
  }
  
  // the way index to start at
  val waymodulus = RegInit(UInt(params.channelCount.idxBits.W), 0.U)
  // places a hold on some channels that have already consumed their data
  val hold = RegInit(VecInit(Seq.fill(params.channelCount)(false.B)))
  // true iff all previously processed channels are ready
  var allPrevReady = true.B
  // in case the first channel is not ready, report zero
  io.in_compressor.ready := 0.U
  // loop over input characters
  for(i <- 0 until params.channelCount) {
    // channel index corresponding to this input character
    val way = (waymodulus +& i.U).div(params.channelCount)._2
    
    // pass this input character to the proper channel
    huffman.io.compressionInputs(way).dataIn(0) := io.in_compressor.bits(i)
    
    val ready = huffman.io.compressionInputs(way).ready
    // true iff this input character is valid
    val valid = i.U < io.in_compressor.valid
    // valid if the input character is valid and there is no hold
    huffman.io.compressionInputs(way).valid := valid && !hold(way)
    // if this input character is accepted, update the hold
    when(valid && ready) {
      // if we are progressing past this input character, clear hold
      // if we will receive this input character again, set hold
      hold(way) := !allPrevReady
    }
    
    // if up to this character is accepted, then set waymodulus to this channel
    // will be overridden if this input character is accepted
    if(i != 0)
    when(allPrevReady && i.U < io.in_compressor.valid) {
      waymodulus := way
    }
    
    // generate new ready signal that reflects this channel
    // ready is true iff all previously processed channels are ready
    allPrevReady &&= ready
    // if this channel and all previous channels are ready, set ready count
    // will be overridden if the next channel is ready
    when(allPrevReady) {
      io.in_compressor.ready := (i + 1).U
    }
    
    // // handle termination
    // if(params.huffman.variableCompression)
    // when(io.in_compressor.finished) {
    //   // stop by setting the compression limit to the current byte
    //   huffman.io.compressionInputs(way).compressionLimit.get :=
    //     huffman.io.compressionInputs(way).currentByteOut +
    //     io.in_compressor.valid - i.U
    // } otherwise {
    //   // set the compression limit as high as possible
    //   huffman.io.compressionInputs(way).compressionLimit.get :=
    //     params.maxCompressionLimit.U
    // }
  }
  
  // if all channels consumed input, waymodulus stays the same
  when(allPrevReady && params.channelCount.U === io.in_compressor.valid) {
    waymodulus := waymodulus
  }
  
  
  //============================================================================
  // COMPRESSOR OUTPUT
  //============================================================================
  
  io.out.zip(huffman.io.outputs zip huffman.io.compressionInputs)
      .foreach {case (out, (subout, subin)) =>
    
    // packed starting from most-significant bit (1234xxxx)
    Iterator.from(0)
      .map(_ * params.compressedCharBits)
      .sliding(2)
      .map(i => subout.dataOut(i(1) - 1, i(0)))
      .zip(out.data.reverse.iterator)
      .foreach{o => o._2 := o._1}
    
    // make output valid as a chunk
    out.valid := Mux(subout.valid && subout.ready, subout.dataLength, 0.U)
    
    subout.ready := out.ready * params.compressedCharBits.U >= subout.dataLength
    
    out.finished := io.in_compressor.finished &&
      (subin.ready || !subin.valid) &&
      (subout.ready || !subout.valid)
  }
}

object HuffmanCompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanCompressor(params), args)
}
