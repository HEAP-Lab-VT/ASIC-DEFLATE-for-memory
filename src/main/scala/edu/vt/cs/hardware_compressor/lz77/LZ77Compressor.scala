package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import Parameters._
import chisel3._
import chisel3.util._

class LZ77Compressor(params: Parameters) extends Module {
  
  val io = IO(new StreamBundle(
    params.compressorCharsIn, UInt(params.characterBits.W),
    params.compressorCharsOut, UInt(params.characterBits.W)))
  
  val cam = Module(new CAM(params))
  val encoder = Module(new Encoder(params))
  
  val moreLiterals = RegInit(false.B)
  
  cam.io.charsIn <> io.in // this is why camCharsIn = compressorCharsIn
  
  when(encoder.io.working && !moreLiterals) {
    // if encoder is working, disconnect CAM
    io.in.ready := 0.U
    cam.io.charsIn.valid := 0.U
    cam.io.charsIn.bits := DontCare
    cam.io.charsIn.finished :=
      io.in.finished && io.in.valid === 0.U
  }
  
  // connect CAM to encoder
  encoder.io.matchLength := cam.io.matchLength
  encoder.io.matchCAMAddress := cam.io.matchCAMAddress
  
  // output literal
  val midEscape = RegInit(false.B)
  midEscape := midEscape && io.out.ready === 0.U
  cam.io.maxLiteralCount := 0.U
  io.out.bits := DontCare
  when(midEscape) {io.out.bits(0) := params.escapeCharacter.U}
  for(index <- 0 to params.camCharsIn) {
    val outindex = index.U +& midEscape +&
      (PopCount(cam.io.charsIn.bits.take(index)
        .map(_ === params.escapeCharacter.U)))
    
    when(outindex < io.out.ready) {
      cam.io.maxLiteralCount := (index + 1).U
    }
    
    if(index < params.camCharsIn)
    when(outindex < io.out.bits.length.U) {
      io.out.bits(outindex) := cam.io.charsIn.bits(index)
      when(cam.io.charsIn.bits(index) === params.escapeCharacter.U) {
        when(outindex +& 1.U < io.out.bits.length.U) {
          io.out.bits(outindex + 1.U) := params.escapeCharacter.U
        }
        when(outindex +& 1.U === io.out.ready && index.U < cam.io.literalCount){
          midEscape := true.B
        }
      }
    }
  }
  
  // output encoding
  val outLitCount = cam.io.literalCount +& midEscape +& (
    PopCount(cam.io.charsIn.bits.zipWithIndex
      .map(c => c._1 === params.escapeCharacter.U &&
        c._2.U < cam.io.literalCount)))
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
    outLitCount +& encoder.io.out.valid <= params.compressorCharsOut.U
}

object LZ77Compressor extends App {
  val params = Parameters.fromCSV("configFiles/lz77.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new LZ77Compressor(params), args)
}
