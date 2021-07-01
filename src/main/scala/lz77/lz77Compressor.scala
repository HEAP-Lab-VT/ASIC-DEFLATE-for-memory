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
  
  val inputBuffer = Reg(Vec(params.camMaxCharsIn, UInt(params.characterBits.W)))
  val inputBufferLength = RegInit(0.U(params.camMaxCharsInBits.W))
  
  when(encoder.io.out.finished || moreLiterals) {
    // if encoder is not working, connect CAM
    io.in.ready := params.camMaxCharsIn.U - inputBufferLength
    cam.io.charsIn.valid :=
      (inputBufferLength + io.in.valid) min params.camMaxCharsIn.U
    cam.io.charsIn.finished := false.B
    for(i <- 0 until params.camMaxCharsIn)
      cam.io.charsIn.bits(i) := Mux(i.U < inputBufferLength,
        inputBuffer(i),
        io.in.bits(i.U - inputBufferLength))
    
    inputBuffer := DontCare
    for(i <- 0 until params.camMaxCharsIn)
      when(i.U + cam.io.charsIn.ready < inputBufferLength) {
        inputBuffer(i) := inputBuffer(i.U + cam.io.charsIn.ready)
      }.elsewhen(i.U + cam.io.charsIn.ready - inputBufferLength < io.in.valid) {
        inputBuffer(i) := io.in.bits(i.U + cam.io.charsIn.ready - inputBufferLength)
      }
    inputBufferLength := Mux(cam.io.charsIn.ready <= cam.io.charsIn.valid,
      cam.io.charsIn.valid - cam.io.charsIn.ready, 0.U)
    
    when(io.in.finished) {
      cam.io.charsIn.finished := inputBufferLength === 0.U
      cam.io.charsIn.valid := inputBufferLength
    }
  } otherwise {
    // if encoder is working, disconnect CAM
    io.in.ready := 0.U
    cam.io.charsIn.valid := 0.U
    cam.io.charsIn.bits := DontCare
    cam.io.charsIn.finished := false.B
  }
  
  // limit literal count based on io.out.ready
  cam.io.maxLiteralCount := 0.U
  for(index <- 1 to params.camMaxCharsIn)
    when(index.U +
        PopCount(
          cam.io.charsIn.bits
          .take(index)
          .map(_ === params.escapeCharacter.U)
        ) <= io.out.ready
        && index.U <= cam.io.charsIn.valid) {
      cam.io.maxLiteralCount := index.U
    }
  
  // assert ready signal to encoder
  encoder.io.out.ready :=
    Mux(io.out.ready > camLitCount, io.out.ready - camLitCount, 0.U)
  
  // connect CAM to encoder
  when(!cam.io.finished) {
    encoder.io.matchLength := cam.io.matchLength
    encoder.io.matchCAMAddress := cam.io.matchCAMAddress
  } otherwise {
    encoder.io.matchLength := 0.U
    encoder.io.matchCAMAddress := DontCare
  }
  
  // output literal
  io.out.bits := DontCare
  for(index <- 0 until params.compressorMaxCharacters) {
    val outindex = index.U +
      (PopCount(cam.io.charsIn.bits.take(index)
        .map(_ === params.escapeCharacter.U)) >> 1)
    when(outindex < io.out.bits.length.U) {
      io.out.bits(outindex) := cam.io.charsIn.bits(index)
      when(cam.io.charsIn.bits(index) === params.escapeCharacter.U &&
          outindex + 1.U < io.out.bits.length.U) {
        io.out.bits(outindex + 1.U) := params.escapeCharacter.U
      }
    }
  }
  
  // output encoding
  val outLitCount = camLitCount + (
    PopCount(cam.io.charsIn.bits.zipWithIndex
      .map(c => c._1 === params.escapeCharacter.U && c._2.U < camLitCount)) >> 1)
  for(index <- 0 until params.compressorMaxCharactersOut)
    when(outLitCount < (io.out.bits.length - index).U) {
      io.out.bits(index.U + outLitCount) := encoder.io.out.bits(index)
    }
  
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
