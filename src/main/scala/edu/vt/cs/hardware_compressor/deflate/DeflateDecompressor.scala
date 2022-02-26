package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.huffman._
import edu.vt.cs.hardware_compressor.lz77._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


class DeflateDecompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Vec(params.encChannels,
      Flipped(DecoupledStream(params.decompressorCharsIn,
        UInt(params.encCharBits.W))))
    val out = DecoupledStream(params.decompressorCharsOut,
      UInt(params.plnCharBits.W))
  })
  
  
  val lz = Module(new LZ77Decompressor(params.lz))
  val huffman = Module(new HuffmanDecompressor(params.huffman))
  // val serializer = Module(new Serializer(params))
  val buffer = Module(new StreamBuffer(
    params.huffman.decompressorCharsOut
    params.lz.decompressorCharsIn,
    params.decompressorIntBufferSize,
    UInt(params.intCharBits.W),
    true))
  
  huffman.io.in <> io.in
  buffer.io.in <> huffman.io.out
  lz.io.in <> buffer.io.out
  io.out <> lz.io.out
}
