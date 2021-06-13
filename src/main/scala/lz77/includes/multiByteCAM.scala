package multiByteCAM

import chisel3._
import chisel3.util._
import lz77Parameters._

class multiByteCAM(params: lz77Parameters) extends Module {
  
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    // val writeData = Input(
    //   Vec(params.compressorMaxCharacters, UInt(params.characterBits.W)))
    val writeDataLength = Input(UInt(params.compressorMaxCharactersBits.W))
    
    // This input allows for search values to be requested from the CAM.
    val searchPattern =
      Input(Vec(params.camMaxPatternLength, UInt(params.characterBits.W)))
    val searchPatternLength = Input(UInt(params.camCharacterSequenceLengthBits.W))
    
    val continueCAMIndex = Input(UInt(params.camAddressBits.W))
    val continueExistingLength = Input(UInt(log2Ceil(params.minCharactersToEncode).W))
    
    // This output is the vector of whether each byte in the cam is a match or not.
    val matchPatternIndex = Output(UInt(params.camCharacterSequenceLengthBits.W))
    val matchCAMIndex = Output(UInt(params.camAddressBits.W))
    val matchLength = Output(UInt(params.camCharacterSequenceLengthBits.W))
    
    // This output is only used when the multiByteCAM is being used by the lz77Compressor.
    // val camHistory =
    //   if (params.camHistoryAvailable)
    //     Some(Output(Vec(params.camCharacters, UInt(params.characterBits.W))))
    //   else None
  })
  
  // This stores the byte history of the CAM.
  val byteHistory = Mem(params.camCharacters, UInt(params.characterBits.W))
  // This stores the number of bytes currently stored in the CAM.
  val camBytes = RegInit(UInt(params.camCharacterCountBits.W), 0.U)
  
  // This handles the write data logic.
  when(camBytes < params.camCharacters.U) {
    when(io.writeData.valid) {
      byteHistory(camBytes) := io.writeData.bits
      camBytes := camBytes + 1.U
    }
  }
  
  // merges byteHistory with searchPattern
  val history = Wire(Vec(params.camCharacters, UInt(params.characterBits.W)))
  for(i <- 0 until params.camCharacters) history(i) := byteHistory(i)
  for(i <- 0 until params.camMaxPatternLength)
    when(i.U < io.searchPatternLength) {
      history(i.U + camBytes) := io.searchPattern(i)
    }
  
  io.matchPatternIndex := params.camMaxPatternLength.U
  for(patternIndex <- 0 until params.camMaxPatternLength reverse) {
    for(camIndex <- 0 until params.camCharacters) {
      when(
        io.searchPattern.drop(patternIndex)
          .zip(history.drop(camIndex))
          .take(params.minCharactersToEncode)
          .map{case (sp, bh) => sp === bh}
          .zipWithIndex
          .map{case (m, i) =>
            m || (i + patternIndex).U >= io.searchPatternLength}
          .reduce(_ && _)
        &&
        (camIndex - patternIndex).U < byteHistory
      ) {
        io.matchPatternIndex := index.U
      }
    }
  }
  
  when(io.continueExistingLength =/= 0.U &&
    io.searchPattern
      .zip(0 until params.minCharactersToEncode
        .map(_.U + io.continueCAMIndex)
        .map(i => byteHistory(i)))
      .map{case (sp, bh) => sp === bh}
      .zipWithIndex
      .map{case (m, i) =>
        m || (params.minCharactersToEncode - i).U <= io.continueExistingLength}
      .reduce(_ && _)
  ) {
    io.matchPatternIndex := 0.U
  }
  
  
  // for (index <- 0 until params.camMaxPatternLength) {
  //   if (index == 0) {
  //     matchVecWire(index) := cam.io.matches(index)
  //   } else {
  //     matchVecWire(index) := (matchVecWire(index - 1).asUInt & (cam.io
  //       .matches(index)
  //       .asUInt >> index)).asBools
  //   }
  // 
  //   val testWire = Wire(Vec(params.camCharacters, Bool()))
  //   testWire := Reverse(matchVecWire(index).asUInt).asBools
  //   matchedIndices(index) := (params.camCharacters - 1).U - testWire
  //     .lastIndexWhere((matchBool: Bool) => { matchBool === true.B })
  //   // When there is still a match available, and the match isn't past the current compressor index, continue and update the necessary wires.
  //   when(
  //     matchVecWire(index).asUInt.orR && (matchedIndices(index) + index.U) < io.patternData.currentCompressorIndex
  //   ) {
  //     matchLength := (index + 1).U
  //   }
  // }
}

object multiByteCAM extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new multiByteCAM(lz77Config))
}
