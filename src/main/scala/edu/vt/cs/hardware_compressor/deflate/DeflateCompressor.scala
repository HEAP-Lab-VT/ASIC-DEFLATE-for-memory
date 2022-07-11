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


class DeflateCompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(RestartableDecoupledStream(params.compressorCharsIn,
      UInt(params.characterBits.W)))
    val out = RestartableDecoupledStream(params.compressorBitsOut, Bool())
  })
  
  
  val lz = Module(new LZCompressor(params.lz))
  val huffman = Module(new HuffmanCompressor(params.huffman))
  // input => lz
  lz.io.in.data := DontCare
  (lz.io.in.data zip io.in.data).foreach(d => d._1 := d._2)
  lz.io.in.valid := io.in.valid min params.lz.compressorCharsIn.U
  io.in.ready := lz.io.in.ready min params.compressorCharsIn.U
  lz.io.in.last := io.in.last
  // lz => huffman
  huffman.io.in.data := DontCare
  (huffman.io.in.data zip lz.io.out.data).foreach(d => d._1 := d._2)
  huffman.io.in.valid := lz.io.out.valid min params.huffman.compressorCharsIn.U
  lz.io.out.ready := huffman.io.in.ready min params.lz.compressorCharsOut.U
  huffman.io.in.last := lz.io.out.last
  // huffman => output
  io.out.data := DontCare
  (io.out.data zip huffman.io.out.data).foreach(d => d._1 := d._2)
  io.out.valid := huffman.io.out.valid min params.compressorBitsOut.U
  huffman.io.out.ready := io.out.ready min
    params.huffman.compressorBitsOut.U
  io.out.last := huffman.io.out.last
  
  // restart signals
  lz.reset := reset.asBool || huffman.io.in.restart
  io.in.restart := huffman.io.in.restart
  huffman.io.out.restart := io.out.restart
}

object DeflateCompressor extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/deflate.csv"))
  Using(new PrintWriter("build/DeflateParameters.h")){pw =>
    params.genCppDefines(pw)
  }
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateCompressor(params), args)
}
