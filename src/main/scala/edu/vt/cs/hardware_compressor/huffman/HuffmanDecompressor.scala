package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.ArithmeticOps._
import edu.vt.cs.hardware_compressor.util.WidthOps._


// Note: This module uses push input and pull output to facilitate block-style
//  input and output, so one or more universal connectors may be necessary to
//  avoid deadlock and/or circular logic. See documentation for DecoupledStream.
class HuffmanDecompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Vec(params.channelCount, Flipped(DecoupledStream(
      params.decompressorCharsIn, UInt(params.compressedCharBits.W))))
    val out = DecoupledStream(params.decompressorCharsOut,
      UInt(params.characterBits.W))
  })
  
  // wrapped module
  val huffman =
    Module(new huffmanDecompressor.huffmanDecompressor(params.huffman))
  
  // generate a rising edge
  // huffman.io.start := RegNext(true.B, false.B);
  // actually, the decompressor doesn't need a rising edge
  huffman.io.start := true.B
  
  
  //============================================================================
  // DECOMPRESSOR INPUT
  //============================================================================
  
  // This is a workaround. We must detect escape codewords and artificially
  // advance the input when one occures. In order to do that, we must
  // partially parse the metadata in order to know what the escape codeword
  // is.
  val hsWaiting :: hsLoadingCompressionLength :: hsLoadingMetadata :: hsDecompressing :: Nil = Enum(4)
  val huffmanStateReg = RegInit(hsWaiting);
  val huffmanState = WireDefault(huffmanStateReg)
  huffmanStateReg := huffmanState
  when(huffmanStateReg === hsWaiting && huffman.io.start) {
    huffmanStateReg := (
      if(params.huffman.variableCompression)
        hsLoadingCompressionLength
      else
        hsLoadingMetadata
    )
  }
  if(params.huffman.variableCompression)
  when(huffmanStateReg === hsLoadingCompressionLength &&
      huffman.io.dataIn(0).valid) {
    huffmanStateReg := hsLoadingMetadata
  }
  val prevCurrentBit = RegNext(huffman.io.currentBit(0))
  when(huffmanStateReg === hsLoadingMetadata &&
      huffman.io.currentBit(0) === 0.U && prevCurrentBit =/= 0.U) {
    huffmanState := hsDecompressing
  }
  
  val escapeCodeword = Reg(UInt(params.huffman.codewordMaxBits.W))
  val escapeCodewordMask = Reg(UInt(params.huffman.codewordMaxBits.W))
  when(huffmanState === hsLoadingMetadata && huffman.io.dataIn(0).valid &&
      huffman.io.dataIn(0).bits(params.huffman.decompressorInputBits - 1)) {
    val maxLen = params.huffman.codewordMaxBits
    val len =
      huffman.io.dataIn(0).bits(params.huffman.codewordLengthMaxBits - 1, 0)
    val shift = maxLen.U - len
    escapeCodeword := huffman.io.dataIn(0).bits
      .>>(params.huffman.codewordLengthMaxBits)
      .<<(shift)
    escapeCodewordMask := ((1.U << len) - 1.U) << shift
  }
  def isEscapeCodeword(input: UInt,
      width: Int = params.huffman.decompressorInputBits): Bool =
    huffmanState === hsDecompressing &&
    (escapeCodeword === (escapeCodewordMask &
      (input >> (width - params.huffman.codewordMaxBits))))
  
  
  val inputDone = WireDefault(Bool(), true.B)
  for(i <- 0 until params.channelCount) {
    
    // We do not know how many bits were consumed by the decompressor until one
    // cycle later. So we have to consume the maximum amount and buffer whatever
    // the decompressor does not accept. This complicates the processing of
    // ready-valid.
    
    // 'buffer' holds the characters that were input to the decompressor in the
    // previous cycle. In the current cycle, we can see how many of those were
    // consumed in the previous cycle and re-input the ones that have not yet
    // been consumed.
    
    val buffer = Reg(Vec(params.decompressorCharsIn,
      UInt(params.compressedCharBits.W)))
    val bufferLength = RegInit(UInt(params.decompressorCharsIn.valBits.W), 0.U)
    val bufferBase =
      RegInit(UInt(params.huffman.parallelCharactersBitAddressBits.W), 0.U)
    
    val current = Wire(Vec(params.decompressorCharsIn,
      UInt(params.compressedCharBits.W)))
    
    val currentAddress = huffman.io.currentBit(i)
    val advance = WireDefault(currentAddress - bufferBase)
    if(i == 0)
    when(currentAddress === 0.U && bufferBase =/= 0.U) {
      // This is a workaround because Chandler's decompressor resets
      // `currentBit` to 0 after processing metadata. So we must guess how many
      // bits were consumed on the last cycle of processing metadata.
      advance := params.huffman.decompressorInputBits.U
    }
    when(currentAddress =/= bufferBase &&
        isEscapeCodeword(buffer.reduce(_ ## _))) {
      // This is a workaround because Chandler's decompressor does not properly
      // advance `currentBit` for escape sequences.
      advance := currentAddress - bufferBase + params.huffman.characterBits.U
    }
    for(j <- 0 until params.decompressorCharsIn) {
      val bufIdx = advance + j.U
      current(j) := Mux(bufIdx < bufferLength, buffer(bufIdx),
        io.in(i).data(bufIdx - bufferLength))
    }
    io.in(i).ready := params.decompressorCharsIn.U - bufferLength + advance
    
    buffer := current
    val allValid = bufferLength - advance +& io.in(i).valid
    bufferLength := allValid min params.decompressorCharsIn.U
    bufferBase := currentAddress
    
    huffman.io.dataIn(i).bits := current.reduce(_ ## _)
    huffman.io.dataIn(i).valid := allValid >= params.decompressorCharsIn.U ||
      (io.in(i).finished && allValid =/= 0.U)
    
    
    // Because the compressor does not know the number of characters to compress
    // in advance, the variable compression length is not properly encoded. This
    // causes the nested decompressor to hang (and wait for more data) even
    // after all the data is processed. So we cannot report the
    // 'last'/'finished' signal on the output based on the 'finished' signal of
    // the nested decompressor. Instead we must infer when the output is
    // finished based on when the input is finished. This can be done easily
    // because the nested decompressor always outputs the decompressed data in
    // the same cycle as it was input the corresponding compressed data. In any
    // case, this is why we need this 'inputLast' wire; it is used later to
    // infer when the output is finished.
    when(!io.in(i).finished || allValid =/= 0.U) {
      inputDone := false.B
    }
  }
  
  
  //============================================================================
  // DECOMPRESSOR OUTPUT
  //============================================================================
  
  huffman.io.dataOut.foreach{output =>
    output.ready := DontCare
  }
  
  val waymodulus = Reg(UInt(params.channelCount.idxBits.W))
  val hold = RegInit(VecInit(Seq.fill(params.channelCount)(false.B)))
  val holdData = Reg(Vec(params.channelCount, UInt(params.characterBits.W)))
  
  io.out.last := inputDone
  var allPrevValid = true.B
  io.out.valid := 0.U
  for(i <- 0 until params.channelCount) {
    val way = (waymodulus +& i.U).div(params.channelCount)._2
    
    when(!hold(way)) {
      io.out.data(i) := huffman.io.dataOut(way).bits
      holdData(way) := huffman.io.dataOut(way).bits
    } otherwise {
      io.out.data(i) := holdData(way)
    }
    
    val ready = i.U <= io.out.ready
    val valid = huffman.io.dataOut(way).valid || hold(way)
    
    huffman.io.dataOut(way).ready := ready && !hold(way)
    
    when(ready && valid) {
      hold(way) := !allPrevValid
    }
    
    if(i != 0)
    when(allPrevValid && i.U < io.out.ready) {
      waymodulus := way
    }
    
    when(valid && !allPrevValid) {
      io.out.last := false.B
    }
    
    allPrevValid &&= valid
    when(allPrevValid) {
      io.out.valid := (i + 1).U
    }
  }
  
  when(allPrevValid && params.channelCount.U === io.out.ready) {
    waymodulus := waymodulus
  }
}

object HuffmanDecompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanDecompressor(params), args)
}
