package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.huffman._
import edu.vt.cs.hardware_compressor.lz77._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


class DeflateCompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(params.compressorCharsIn,
      UInt(params.plnCharBits.W)))
    val out = Vec(params.encChannels,
      DecoupledStream(params.compressorCharsOut,
        UInt(params.encCharBits.W)))
  })
  
  
  val lz = Module(new LZ77Compressor(params.lz))
  val huffman = Module(new HuffmanCompressor(params.huffman))
  // val serializer = Module(new Serializer(params))
  val tee = Module(new StreamTee(
    params.lz.compressorCharsOut,
    Array(
      params.huffman.counterCharsIn,
      params.huffman.compressorCharsIn),
    params.compressorIntBufSize,
    UInt(params.intCharBits.W),
    true,
    false))
  
  lz.io.in <> io.in
  tee.io.in <> lz.io.out
  huffman.io.in_counter <> tee.io.out(0)
  huffman.io.in_compressor <> tee.io.out(1)
  io.out <> huffman.io.out
}

object DeflateCompressor extends App {
  val params = Parameters.fromCSV("configFiles/deflate.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateCompressor(params), args)
}
