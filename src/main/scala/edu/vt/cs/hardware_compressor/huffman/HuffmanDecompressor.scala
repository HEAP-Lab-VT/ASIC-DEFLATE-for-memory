package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
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
  
  huffman.io.start := true.B
  
  
  //============================================================================
  // DECOMPRESSOR INPUT
  //============================================================================
  
  for(i <- 0 until params.channelCount) {
    io.in(i).ready := huffman.io.inReady(i)
    huffman.io.dataIn(i).bits := io.in(i).data.reduce(_ ## _)
    huffman.io.dataIn(i).valid :=
      io.in(i).valid >= params.decompressorCharsIn.U ||
      (io.in(i).finished && io.in(i).valid =/= 0.U)
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
  
  io.out.last := io.in.map(i => i.last && i.valid === 0.U).reduce(_ && _)
  var allPrevValid = WireDefault(true.B)
  io.out.valid := 0.U
  for(i <- 0 until params.channelCount) {
    val way = (waymodulus +& i.U) % params.channelCount.U
    
    when(!hold(way)) {
      io.out.data(i) := huffman.io.dataOut(way).bits
      holdData(way) := huffman.io.dataOut(way).bits
    } otherwise {
      io.out.data(i) := holdData(way)
    }
    
    val ready = i.U < io.out.ready
    val valid = huffman.io.dataOut(way).valid || hold(way)
    
    huffman.io.dataOut(way).ready := ready && !hold(way)
    
    when(ready && valid) {
      hold(way) := !allPrevValid
    }
    
    if(i != 0)
    when(allPrevValid && i.U <= io.out.ready) {
      waymodulus := way
    }
    
    when(valid && !allPrevValid) {
      io.out.last := false.B
    }
    
    dontTouch apply allPrevValid.suggestName(s"allPrevValid_$i")
    dontTouch apply way.suggestName(s"way_$i")
    dontTouch apply ready.suggestName(s"ready_$i")
    dontTouch apply valid.suggestName(s"valid_$i")
    
    allPrevValid &&= valid
    when(allPrevValid) {
      io.out.valid := (i + 1).U
    }
  }
  
  dontTouch apply allPrevValid.suggestName(s"allPrevValid_${params.channelCount}")
  
  when(allPrevValid && params.channelCount.U === io.out.ready) {
    waymodulus := waymodulus
  }
}

object HuffmanDecompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanDecompressor(params), args)
}
