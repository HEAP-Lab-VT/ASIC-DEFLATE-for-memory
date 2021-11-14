package edu.vt.cs.hardware_compressor.deflate

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.lz77._
import chisel3._
import chisel3.util._
import huffmanCompressor
// each class in a seperate package SMH

class DeflateCompressor(params: Parameters) extends Module {
  val io = IO(new StreamBundle(
    params.compressorCharsIn, UInt(params.characterBits.W),
    params.compressorCharsOut, UInt(params.characterBits.W)))
  
  
  val lz = Module(new LZ77Compressor(params.lz))
  val huffman = Module(new huffmanCompressor(params.huffman))
  
  
}
