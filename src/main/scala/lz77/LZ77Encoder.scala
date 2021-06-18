package lz77.util

import chisel3._
import chisel3.util._
import lz77Parameters._

class LZ77Encoder(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    val out = DecoupledStream(params.compressorMaxCharactersOut, UInt(params.characterBits.W))
    val matchLength = Input(UInt(params.camCharacterSequenceLengthBits.W))
    val matchCAMAddress = Input(UInt(params.camAddressBits.W))
  })
  
  val remainingLengthReg = RegInit(0.U(params.camCharacterSequenceLengthBits.W))
  val minEncodingReg = Reg(Vec(params.minEncodingWidth / params.characterBits, UInt(params.characterBits.W)))
  val minEncodingIndexReg = RegInit(log2Ceil(params.minEncodingWidth / params.characterBits + 1).U)
  
  val remainingLength = WireDefault(remainingLengthReg)
  val minEncoding = WireDefault(minEncodingReg)
  val minEncodingIndex = WireDefault(minEncodingIndexReg)
  
  remainingLengthReg := remainingLength
  minEncodingReg := minEncoding
  minEncodingIndexReg := minEncodingIndex
  
  io.out.finished := remainingLengthReg === 0.U
  
  when(io.matchLength =/= 0.U) {
    remainingLength := io.matchLength + (params.minEncodingWidth / params.characterBits * params.extraCharacterLengthIncrease - params.maxCharactersInMinEncoding).U
    
    val minEncodingUInt = params.escapeCharacter.U ## params.escapeCharacter.U.apply(params.characterBits - 1) ## io.matchCAMAddress ## (((io.matchLength min params.maxCharactersInMinEncoding.U) - params.minCharactersToEncode.U)(params.minEncodingSequenceLengthBits - 1, 0))
    minEncoding := (0 until (minEncodingWidth / characterBits) reverse).map{i => minEncodingUInt((i + 1) * params.characterBits - 1, i * params.characterBits)}
    minEncodingIndex := 0.U
  }
  
  io.out.valid := 0.U
  io.out.bits := DontCare
  for(index <- 0 until params.compressorMaxCharactersOut) {
    val output = io.out.bits(index)
    when(minEncodingIndex < (minEncodingWidth / characterBits - index).U) {
      output := minEncodingChars(minEncodingIndex + index.U)
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - (index * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := minEncodingIndex + index + 1
      }
    } elsewhen(remainingLength > ((index + 1) * params.extraCharacterLengthIncrease).U) {
      output := params.maxCharacterValue.U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - (index * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := (minEncodingWidth / characterBits).U
      }
    } elsewhen(remainingLength > (index * params.extraCharacterLengthIncrease).U) {
      output := remainingLength - (index * params.extraCharacterLengthIncrease + 1).U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := 0
        minEncodingIndexReg := (minEncodingWidth / characterBits).U
      }
    } otherwise {
      // output := DontCare
      // when(index.U < io.out.ready) {
      //   remainingLengthReg := 0
      //   minEncodingIndexReg := (minEncodingWidth / characterBits).U
      // }
    }
  }
}
