package edu.vt.cs.hardware_compressor.deflate

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.lz77._
import chisel3._
import chisel3.util._
// each class in a seperate package SMH
// import huffmanCompressor.huffmanCompressor


class DeflateCompressor(params: Parameters) extends Module {
  val io = IO(new StreamBundle(
    params.compressorCharsIn, UInt(params.characterBits.W),
    params.compressorCharsOut, UInt(params.characterBits.W)))
  
  
  val lz = Module(new LZ77Compressor(params.lz))
  val huffman = Module(new huffmanCompressor(params.huffman))
  val serializer = Module(new Serializer(params))
  val intermediateBuffer = Module(new StreamBuffer(lz.compressorCharsIn,
    /*huffman input characters*/, /*chars to compress*/,
    UInt(params.characterBits.W), true))
  
  io.in <> lz.io.in
  lz.out <> intermediateBuffer.in
}
