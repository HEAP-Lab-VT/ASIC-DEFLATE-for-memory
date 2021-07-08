package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import chisel3._
import chisel3.util._

class LZ77Encoder(params: Parameters) extends Module {
  val io = IO(new Bundle {
    val out = DecoupledStream(params.compressorMaxCharactersOut, UInt(params.characterBits.W))
    val matchLength = Input(UInt(params.patternLengthBits.W))
    val matchCAMAddress = Input(UInt(params.camAddressBits.W))
    
    val working = Output(Bool())
  })
  
  val remainingLengthType = UInt(log2Ceil(params.maxPatternLength + (params.minEncodingWidth / params.characterBits * params.extraCharacterLengthIncrease - params.maxCharactersInMinEncoding) + 1).W)
  val minEncodingType = Vec(params.minEncodingWidth / params.characterBits, UInt(params.characterBits.W))
  val minEncodingIndexType = UInt(log2Ceil(params.minEncodingWidth / params.characterBits + 1).W)
  
  val remainingLengthReg = RegInit(remainingLengthType, 0.U)
  val minEncodingReg = Reg(minEncodingType)
  val minEncodingIndexReg = RegInit(minEncodingIndexType, (params.minEncodingWidth / params.characterBits).U)
  
  val remainingLength = WireDefault(remainingLengthType, remainingLengthReg)
  val minEncoding = WireDefault(minEncodingReg)
  val minEncodingIndex = WireDefault(minEncodingIndexType, minEncodingIndexReg)
  
  io.working := remainingLengthReg =/= 0.U
  
  when(io.matchLength =/= 0.U) {
    remainingLength := Mux(io.matchLength > params.maxCharactersInMinEncoding.U,
      io.matchLength +& (((params.minEncodingWidth / params.characterBits) * params.extraCharacterLengthIncrease) - params.maxCharactersInMinEncoding).U,
      ((params.minEncodingWidth / params.characterBits) * params.extraCharacterLengthIncrease).U)
    
    val escape = params.escapeCharacter.U(params.characterBits.W)
    val escapeconfirmation = ~escape(params.characterBits - 1)
    val address = io.matchCAMAddress
    val length = ((io.matchLength - params.minCharactersToEncode.U) min ((1 << params.minEncodingSequenceLengthBits) - 1).U)(params.minEncodingSequenceLengthBits - 1, 0)
    val minEncodingUInt = escape ## escapeconfirmation ## address ## length
    minEncoding := (0 until (params.minEncodingWidth / params.characterBits) reverse).map{i => minEncodingUInt((i + 1) * params.characterBits - 1, i * params.characterBits)}
    minEncodingIndex := 0.U
    
    remainingLengthReg := remainingLength
    minEncodingReg := minEncoding
    minEncodingIndexReg := minEncodingIndex
  }
  
  
  io.out.finished := remainingLength <= (params.compressorMaxCharactersOut * params.extraCharacterLengthIncrease).U
  io.out.valid := 0.U
  io.out.bits := DontCare
  for(index <- 0 until params.compressorMaxCharactersOut) {
    val output = io.out.bits(index)
    when(minEncodingIndex +& index.U < (params.minEncodingWidth / params.characterBits).U) {
      output := minEncoding(minEncodingIndex + index.U)
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := minEncodingIndex + (index + 1).U
      }
    }.elsewhen(remainingLength > ((index + 1) * params.extraCharacterLengthIncrease).U) {
      output := params.maxCharacterValue.U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := (params.minEncodingWidth / params.characterBits).U
      }
    }.elsewhen(remainingLength > (index * params.extraCharacterLengthIncrease).U) {
      output := remainingLength - (index * params.extraCharacterLengthIncrease + 1).U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := 0.U
        minEncodingIndexReg := (params.minEncodingWidth / params.characterBits).U
      }
    } otherwise {
      // output := DontCare
      // when(index.U < io.out.ready) {
      //   remainingLengthReg := 0.U
      //   minEncodingIndexReg := (minEncodingWidth / characterBits).U
      // }
    }
  }
}
