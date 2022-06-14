package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.huffman._
import edu.vt.cs.hardware_compressor.lz._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


class DeflateDecompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Vec(params.encChannels,
      Flipped(RestartableDecoupledStream(params.decompressorBitsIn, Bool())))
    val out = RestartableDecoupledStream(params.decompressorCharsOut,
      UInt(params.plnCharBits.W))
  })
  
  
  val lz = Module(new LZDecompressor(params.lz))
  val huffman = Module(new HuffmanDecompressor(params.huffman))
  val buffer = Module(new StreamBuffer(
    params.huffman.decompressorCharsOut,
    params.lz.decompressorCharsIn,
    params.decompressorIntBufferSize,
    UInt(params.intCharBits.W),
    true,
    false))
  
  huffman.io.in <> io.in
  buffer.io.in <> huffman.io.out.viewAsDecoupledStream
  lz.io.in <> buffer.io.out
  io.out.viewAsDecoupledStream <> lz.io.out
  
  huffman.io.out.restart := io.out.restart
  lz.reset := reset || io.out.restart
  buffer.reset := reset || io.out.restart
}

object DeflateDecompressor extends App {
  val params = Parameters.fromCSV("configFiles/deflate.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateDecompressor(params), args)
}
