package edu.vt.cs.hardware_compressor.lz

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.nio.file.Path

class LZCompressor(params: Parameters) extends Module {
  
  val io = IO(new StreamBundle(
    params.compressorCharsIn, UInt(params.characterBits.W),
    params.compressorCharsOut, UInt(params.characterBits.W)))
  
  val cam = Module(new CAM(params))
  val encoder = Module(new Encoder(params))
  
  val moreLiterals = RegInit(false.B)
  
  cam.io.charsIn <> io.in // this is why camCharsIn = compressorCharsIn
  cam.io.matchReady := true.B
  
  // connect CAM to encoder
  encoder.io.matchLength := cam.io.matchLength
  encoder.io.matchCAMAddress := cam.io.matchCAMAddress
  
  // output literal
  val midEscape = RegInit(false.B)
  midEscape := midEscape && io.out.ready === 0.U
  cam.io.litOut.ready := 0.U
  io.out.bits := DontCare
  when(midEscape) {io.out.bits(0) := params.escapeCharacter.U}
  for(index <- 0 to params.camCharsPerCycle) {
    val outindex = index.U +& midEscape +&
      (PopCount(cam.io.litOut.bits.take(index)
        .map(_ === params.escapeCharacter.U)))
    
    when(outindex < io.out.ready) {
      cam.io.litOut.ready := (index + 1).U
    }
    
    if(index < params.compressorCharsOut)
    when(outindex < params.compressorCharsOut.U) {
      io.out.bits(outindex) := cam.io.litOut.bits(index)
      when(cam.io.litOut.bits(index) === params.escapeCharacter.U) {
        when(outindex < (params.compressorCharsOut - 1).U) {
          io.out.bits(outindex + 1.U) := params.escapeCharacter.U
        }
        when(outindex +& 1.U === io.out.ready && index.U < cam.io.litOut.valid){
          midEscape := true.B
        }
      }
    }
  }
  
  // literal count including double escapes
  val outLitCount = WireDefault(cam.io.litOut.valid +& midEscape +& (
    PopCount(cam.io.litOut.bits.zipWithIndex
      .map(c => c._1 === params.escapeCharacter.U &&
        c._2.U < cam.io.litOut.valid))))
  
  when(encoder.io.working && !moreLiterals) {
    // if encoder is working, disable CAM
    cam.io.litOut.ready := 0.U
    midEscape := false.B
    cam.io.matchReady := false.B
    encoder.io.matchLength := 0.U
    encoder.io.matchCAMAddress := DontCare
    outLitCount := 0.U
  }
  
  // output encoding
  for(index <- 0 until params.compressorCharsOut)
    when(outLitCount < (io.out.bits.length - index).U) {
      io.out.bits(index.U + outLitCount) := encoder.io.out.bits(index)
    }
  
  encoder.io.out.ready :=
    Mux(outLitCount > io.out.ready, 0.U, io.out.ready - outLitCount)
  moreLiterals := outLitCount > io.out.ready
  
  // calculate valid and finished
  io.out.valid := (outLitCount +& encoder.io.out.valid) min
    params.compressorCharsOut.U
  io.out.finished := cam.io.finished && encoder.io.out.finished &&
    (!encoder.io.working ||
      (cam.io.litOut.valid === 0.U && cam.io.matchLength === 0.U)) &&
    outLitCount +& encoder.io.out.valid <= params.compressorCharsOut.U
}

object LZCompressor extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/lz.csv"))
  new chisel3.stage.ChiselStage()
    .emitVerilog(new LZCompressor(params), args)
}
