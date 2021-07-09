package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import Parameters._
import chisel3._
import chisel3.util._

class LZ77Encoder(params: Parameters) extends Module {
  val io = IO(new Bundle {
    val out = DecoupledStream(params.compressorCharsOut, UInt(params.characterBits.W))
    val matchLength = Input(UInt(params.maxCharsToEncode.valBits.W))
    val matchCAMAddress = Input(UInt(params.camSize.idxBits.W))
    
    val working = Output(Bool())
  })
  
  val remainingLengthType = UInt((params.maxCharsToEncode + (params.minEncodingChars * params.extraCharacterLengthIncrease) - params.maxCharsInMinEncoding).valBits.W)
  val minEncodingType = Vec(params.minEncodingChars, UInt(params.characterBits.W))
  val minEncodingIndexType = UInt(params.minEncodingChars.valBits.W)
  
  val remainingLengthReg = RegInit(remainingLengthType, 0.U)
  val minEncodingReg = Reg(minEncodingType)
  val minEncodingIndexReg = RegInit(minEncodingIndexType, params.minEncodingChars.U)
  
  val remainingLength = WireDefault(remainingLengthType, remainingLengthReg)
  val minEncoding = WireDefault(minEncodingReg)
  val minEncodingIndex = WireDefault(minEncodingIndexType, minEncodingIndexReg)
  
  io.working := remainingLengthReg =/= 0.U
  
  when(io.matchLength =/= 0.U) {
    remainingLength := Mux(io.matchLength > params.maxCharsInMinEncoding.U,
      io.matchLength +& ((params.minEncodingChars * params.extraCharacterLengthIncrease) - params.maxCharsInMinEncoding).U,
      (params.minEncodingChars * params.extraCharacterLengthIncrease).U)
    
    val escape = params.escapeCharacter.U(params.characterBits.W)
    val confirmation = ~escape(params.characterBits - 1)
    val address = io.matchCAMAddress
    val length = ((io.matchLength - params.minCharsToEncode.U) min ((1 << params.minEncodingLengthBits) - 1).U)(params.minEncodingLengthBits - 1, 0)
    val minEncodingUInt = escape ## confirmation ## address ## length
    minEncoding := (0 until params.minEncodingChars reverse).map{i => minEncodingUInt((i + 1) * params.characterBits - 1, i * params.characterBits)}
    minEncodingIndex := 0.U
    
    remainingLengthReg := remainingLength
    minEncodingReg := minEncoding
    minEncodingIndexReg := minEncodingIndex
  }
  
  
  io.out.finished := remainingLength <= (params.compressorCharsOut * params.extraCharacterLengthIncrease).U
  io.out.valid := 0.U
  io.out.bits := DontCare
  for(index <- 0 until params.compressorCharsOut) {
    val output = io.out.bits(index)
    when(minEncodingIndex +& index.U < params.minEncodingChars.U) {
      output := minEncoding(minEncodingIndex + index.U)
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := minEncodingIndex + (index + 1).U
      }
    }.elsewhen(remainingLength > ((index + 1) * params.extraCharacterLengthIncrease).U) {
      output := params.characterBits.maxVal.U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := remainingLength - ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := params.minEncodingChars.U
      }
    }.elsewhen(remainingLength > (index * params.extraCharacterLengthIncrease).U) {
      output := remainingLength - (index * params.extraCharacterLengthIncrease + 1).U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        remainingLengthReg := 0.U
        minEncodingIndexReg := params.minEncodingChars.U
      }
    } otherwise {
      // output := DontCare
      // when(index.U < io.out.ready) {
      //   remainingLengthReg := 0.U
      //   minEncodingIndexReg := params.minEncodingChars.U
      // }
    }
  }
}
