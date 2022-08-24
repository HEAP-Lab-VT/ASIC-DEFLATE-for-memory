package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.huffman.{
  HuffmanCompressor,HuffmanDecompressor}
import edu.vt.cs.hardware_compressor.lz.{LZCompressor,LZDecompressor}
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.PrintWriter
import java.nio.file.Path
import scala.util._


class DeflateDecompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(RestartableDecoupledStream(params.decompressorBitsIn,
      Bool()))
    val out = RestartableDecoupledStream(params.decompressorCharsOut,
      UInt(params.characterBits.W))
  })
  
  
  val lz = Module(new LZDecompressor(params.lz))
  val huffman = Module(new HuffmanDecompressor(params.huffman))
  val buffer = Module(new StreamBuffer(
    params.huffman.decompressorCharsOut,
    params.lz.decompressorCharsIn,
    params.decompressorMidBufferSize,
    UInt(params.characterBits.W),
    true,
    false))
  
  // input => huffman
  huffman.io.in.data := DontCare
  (huffman.io.in.data zip io.in.data).foreach(d => d._1 := d._2)
  huffman.io.in.valid := io.in.valid min params.huffman.decompressorBitsIn.U
  io.in.ready := huffman.io.in.ready min params.decompressorBitsIn.U
  huffman.io.in.last := io.in.last
  // huffman => buffer
  buffer.io.in <> huffman.io.out.viewAsDecoupledStream
  // buffer => lz
  lz.io.in <> buffer.io.out
  // lz => output
  io.out.data := DontCare
  (io.out.data zip lz.io.out.data).foreach(d => d._1 := d._2)
  io.out.valid := lz.io.out.valid min params.decompressorCharsOut.U
  lz.io.out.ready := io.out.ready min params.lz.decompressorCharsOut.U
  io.out.last := lz.io.out.last
  
  // restart signals
  io.in.restart := huffman.io.in.restart
  huffman.io.out.restart := io.out.restart
  buffer.reset := reset.asBool || io.out.restart
  lz.reset := reset.asBool || io.out.restart
}

object DeflateDecompressor extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/deflate.csv"))
  Using(new PrintWriter("build/DeflateParameters.h")){pw =>
    params.genCppDefines(pw)
  }
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateDecompressor(params), args)
}
