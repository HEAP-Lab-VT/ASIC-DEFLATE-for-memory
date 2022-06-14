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
      UInt(params.characterBits.W)))
    val out = RestartableDecoupledStream(params.compressorBitsOut, Bool())
  })
  
  
  val lz = Module(new LZCompressor(params.lz))
  val huffman = Module(new HuffmanCompressor(params.huffman))
  // input => lz
  lz.io.in.data := DontCare
  (lz.io.in.data zip io.in.data).foreach(d => d._1 := d._2)
  lz.io.in.valid := io.in.valid min params.lz.compressorCharsIn.U
  io.in.data.ready := lz.io.in.ready min params.compressorCharsIn.U
  // lz => huffman
  huffman.io.in.data := DontCare
  (huffman.io.in.data zip lz.io.out.data).foreach(d => d._1 := d._2)
  huffman.io.in.valid := lz.io.out.valid min params.huffman.compressorCharsIn.U
  lz.io.out.data.ready := huffman.io.in.ready min params.lz.compressorCharsOut.U
  // huffman => output
  io.out.data := DontCare
  (io.out.data zip huffman.io.out.data).foreach(d => d._1 := d._2)
  io.out.valid := huffman.io.out.valid min params.compressorCharsOut.U
  huffman.io.out.data.ready := io.out.ready min
    params.huffman.compressorCharsOut.U
  
  // restart signals
  lz.reset := reset || huffman.io.in.restart
  io.in.restart := huffman.io.in.restart
  huffman.io.out.restart := io.out.restart
}

object DeflateCompressor extends App {
  val params = Parameters.fromCSV("configFiles/deflate.csv")
  Using(new PrintWriter(new File("build/DeflateParameters.h"))){pw =>
    params.generateCppDefines(pw, "DEFLATE_")
  }
  new chisel3.stage.ChiselStage()
    .emitVerilog(new DeflateCompressor(params), args)
}
