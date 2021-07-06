package lz77Compressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import multiByteCAM._
import lz77.util._

class lz77Compressor(params: lz77Parameters) extends Module {
  
  val io = IO(new StreamBundle(
    params.compressorMaxCharacters, UInt(params.characterBits.W),
    params.compressorMaxCharactersOut, UInt(params.characterBits.W)))
  
  val cam = Module(new multiByteCAM(params))
  val encoder = Module(new LZ77Encoder(params))
  
  val camLitCount = Mux(cam.io.finished, 0.U, cam.io.literalCount)
  val moreLiterals = RegInit(false.B)
  
  val inBuffer = Module(ReadyDecoupler(
    params.compressorMaxCharacters,
    params.compressorMaxCharacters,
    UInt(params.characterBits.W)))
  inBuffer.io.in <> io.in
  cam.io.charsIn <> inBuffer.io.out
  
  when(!encoder.io.out.finished && !moreLiterals) {
    // if encoder is working, disconnect CAM
    inBuffer.io.out.ready := 0.U
    cam.io.charsIn.valid := 0.U
    cam.io.charsIn.bits := DontCare
    // cam.io.charsIn.finished := false.B
  }
  
  // connect CAM to encoder
  when(!cam.io.finished) {
    encoder.io.matchLength := cam.io.matchLength
    encoder.io.matchCAMAddress := cam.io.matchCAMAddress
  } otherwise {
    encoder.io.matchLength := 0.U
    encoder.io.matchCAMAddress := DontCare
  }
  
  // output literal
  val midEscape = RegInit(false.B)
  midEscape := midEscape && io.out.ready === 0.U
  cam.io.maxLiteralCount := 0.U
  io.out.bits := DontCare
  when(midEscape) {io.out.bits(0) := params.escapeCharacter.U}
  for(index <- 0 to params.camMaxCharsIn) {
    val outindex = index.U +& midEscape +&
      (PopCount(cam.io.charsIn.bits.take(index)
        .map(_ === params.escapeCharacter.U)))
    
    when(outindex <= io.out.ready) {
      cam.io.maxLiteralCount := index.U
    }
    
    if(index < params.camMaxCharsIn)
    when(outindex < io.out.bits.length.U) {
      io.out.bits(outindex) := cam.io.charsIn.bits(index)
      when(cam.io.charsIn.bits(index) === params.escapeCharacter.U) {
        when(outindex +& 1.U < io.out.bits.length.U) {
          io.out.bits(outindex + 1.U) := params.escapeCharacter.U
        }
        when(outindex +& 1.U === io.out.ready && index.U < camLitCount) {
          midEscape := true.B
        }
      }
    }
  }
  
  // output encoding
  val outLitCount = camLitCount +& midEscape +& (
    PopCount(cam.io.charsIn.bits.zipWithIndex
      .map(c => c._1 === params.escapeCharacter.U && c._2.U < camLitCount)))
  for(index <- 0 until params.compressorMaxCharactersOut)
    when(outLitCount < (io.out.bits.length - index).U) {
      io.out.bits(index.U + outLitCount) := encoder.io.out.bits(index)
    }
  
  encoder.io.out.ready :=
    Mux(outLitCount > io.out.ready, 0.U, io.out.ready - outLitCount)
  moreLiterals := outLitCount > io.out.ready
  
  // calculate valid and finished
  io.out.valid := (outLitCount +& encoder.io.out.valid) min
    params.compressorMaxCharactersOut.U
  io.out.finished := cam.io.finished && encoder.io.out.finished
}

object lz77Compressor extends App {
  val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new lz77Compressor(params), args)
}
