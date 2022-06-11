package edu.vt.cs.hardware_compressor.lz

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import chisel3._
import chisel3.util._

class Encoder(params: Parameters) extends Module {
  val io = IO(new Bundle {
    val out = DecoupledStream(params.compressorCharsOut,
      UInt(params.characterBits.W))
    val matchLength = Input(UInt(params.maxCharsToEncode.valBits.W))
    val matchCAMAddress = Input(UInt(params.camSize.idxBits.W))
    
    // true iff continuing from previous cycle
    val working = Output(Bool())
  })
  
  // state types
  val remainingLengthType = UInt((params.maxCharsToEncode +
    (params.minEncodingChars * params.extraCharacterLengthIncrease) -
    params.maxCharsInMinEncoding).valBits.W)
  val minEncodingType =
    Vec(params.minEncodingChars, UInt(params.characterBits.W))
  val minEncodingIndexType = UInt(params.minEncodingChars.valBits.W)
  
  // state registers
  // one more than remaining sequence length so end can be properly detected
  val remainingLengthReg = RegInit(remainingLengthType, 0.U)
  val minEncodingReg = Reg(minEncodingType)
  val minEncodingIndexReg =
    RegInit(minEncodingIndexType, params.minEncodingChars.U)
  
  // state wires (allows same-cycle results)
  val remainingLength = WireDefault(remainingLengthType, remainingLengthReg)
  val minEncoding = WireDefault(minEncodingReg)
  val minEncodingIndex = WireDefault(minEncodingIndexType, minEncodingIndexReg)
  
  // assert whether CAM should be disconnected
  io.working := remainingLengthReg =/= 0.U
  
  when(io.matchLength =/= 0.U) {
    // calaculate the remaining sequence length to encode
    // This is decremented by params.extraCharacterLengthIncrease for every
    // encoding byte that is output.
    remainingLength := Mux(io.matchLength > params.maxCharsInMinEncoding.U,
      io.matchLength +& ((params.minEncodingChars *
        params.extraCharacterLengthIncrease) - params.maxCharsInMinEncoding).U,
      (params.minEncodingChars * params.extraCharacterLengthIncrease).U)
    
    // create the encoding header
    val escape = params.escapeCharacter.U(params.characterBits.W)
    val confirmation = ~escape(params.characterBits - 1)
    val address = io.matchCAMAddress
    val length = ((io.matchLength - params.minCharsToEncode.U) min
      ((1 << params.minEncodingLengthBits) - 1).U
      )(params.minEncodingLengthBits - 1, 0)
    val minEncodingUInt = escape ## confirmation ## address ## length
    minEncoding := (0 until params.minEncodingChars).reverse
      .map{i => minEncodingUInt(
        (i + 1) * params.characterBits - 1, i * params.characterBits)}
    minEncodingIndex := 0.U
    
    // update state reginsters to process new encoding
    remainingLengthReg := remainingLength
    minEncodingReg := minEncoding
    minEncodingIndexReg := minEncodingIndex
  }
  
  // process/output encoding
  io.out.finished := remainingLength <=
    (params.compressorCharsOut * params.extraCharacterLengthIncrease).U
  io.out.valid := 0.U
  io.out.bits := DontCare
  for(index <- 0 until params.compressorCharsOut) {
    val output = io.out.bits(index) // alias current output wire
    when(minEncodingIndex +& index.U < params.minEncodingChars.U) {
      // this character is part of the header
      output := minEncoding(minEncodingIndex + index.U)
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        // this character is accepted by consumer
        remainingLengthReg := remainingLength -
          ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := minEncodingIndex + (index + 1).U
      }
    }.elsewhen(remainingLength >
        ((index + 1) * params.extraCharacterLengthIncrease).U) {
      // encoding extends beyond this character
      output := params.characterBits.maxVal.U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        // this character is accepted by consumer
        remainingLengthReg := remainingLength -
          ((index + 1) * params.extraCharacterLengthIncrease).U
        minEncodingIndexReg := params.minEncodingChars.U
      }
    }.elsewhen(remainingLength >
        (index * params.extraCharacterLengthIncrease).U) {
      // this character terminates the encoding
      output := remainingLength -
        (index * params.extraCharacterLengthIncrease + 1).U
      io.out.valid := (index + 1).U
      when(index.U < io.out.ready) {
        // this character is accepted by consumer
        remainingLengthReg := 0.U
        minEncodingIndexReg := params.minEncodingChars.U
      }
    } otherwise {
      // this character is not in an encoding
      
      // output := DontCare
      // when(index.U < io.out.ready) {
      //   remainingLengthReg := 0.U
      //   minEncodingIndexReg := params.minEncodingChars.U
      // }
    }
  }
}
