package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.huffman._
import edu.vt.cs.hardware_compressor.lz._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


class DeflateCompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(RestartableDecoupledStream(params.compressorCharsIn,
      UInt(params.plnCharBits.W)))
    val out = Vec(params.encChannels,
      RestartableDecoupledStream(params.compressorBitsOut, Bool()))
  })
  
  
  val lz = Module(new LZCompressor(params.lz))
  val huffman = Module(new HuffmanCompressor(params.huffman))
  lz.io.in <> io.in.viewAsDecoupledStream
  huffman.io.in.viewAsDecoupledStream <> lz.io.out
  io.out <> huffman.io.out
  
  lz.reset := reset || huffman.io.in.restart
  io.in.restart := huffman.io.in.restart
}

object DeflateCompressor extends App {
  val params = Parameters.fromCSV("configFiles/deflate.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateCompressor(params), args)
}
