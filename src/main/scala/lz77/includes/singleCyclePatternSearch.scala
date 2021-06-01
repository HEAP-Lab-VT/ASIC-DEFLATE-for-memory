package singleCyclePatternSearch

import chisel3._
import chisel3.util._
import lz77Parameters._
import multiByteCAM._
import lz77InputsAndOutputs._

class singleCyclePatternSearch(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    val writeData = Flipped(Valid(UInt(params.characterBits.W)))
    // This input allows for search values to be requested from the CAM.
    val patternData = Input(new searchInputs(params))

    // This tells how many bytes were in the longest match and where it was in the CAM.
    val matchResult = Output(new patternMatch(params))
    // This output is only used when the multiByteCAM is being used by the lz77Compressor.
    val camHistory =
      if (params.camHistoryAvailable)
        Some(Output(Vec(params.camCharacters, UInt(params.characterBits.W))))
      else None
  })

  // This creates the cam and initializes its values
  val cam = Module(new multiByteCAM(params))
  cam.io.writeData <> io.writeData
  cam.io.searchData <> io.patternData.pattern
  if (params.camHistoryAvailable) {
    cam.io.camHistory.get <> io.camHistory.get
  }

  // This will iterate through all the matches and shift them as necessary to detect what the longest character sequence of them is.
  val matchVecWire = Wire(
    Vec(params.camMaxPatternLength, Vec(params.camCharacters, Bool()))
  )
  val matchLength = Wire(UInt(params.camCharacterSequenceLengthBits.W))
  matchLength := 0.U
  // This will hold the index that matches for each level of the character sequence matcher.
  val matchedIndices =
    Wire(Vec(params.camMaxPatternLength, UInt(params.camAddressBits.W)))
  for (index <- 0 until params.camMaxPatternLength) {
    if (index == 0) {
      matchVecWire(index) := cam.io.matches(index)
    } else {
      matchVecWire(index) := (matchVecWire(index - 1).asUInt & (cam.io
        .matches(index)
        .asUInt >> index)).asBools
    }

    val testWire = Wire(Vec(params.camCharacters, Bool()))
    testWire := Reverse(matchVecWire(index).asUInt).asBools
    matchedIndices(index) := (params.camCharacters - 1).U - testWire
      .lastIndexWhere((matchBool: Bool) => { matchBool === true.B })
    // When there is still a match available, and the match isn't past the current compressor index, continue and update the necessary wires.
    when(
      matchVecWire(index).asUInt.orR && (matchedIndices(index) + index.U) < io.patternData.currentCompressorIndex
    ) {
      matchLength := (index + 1).U
    }
  }

  // This finds the index of the first pattern that matches the longest length match, or 0 if there are no matches.
  when(matchLength === 0.U) {
    io.matchResult.patternIndex := 0.U
  }.otherwise {
    io.matchResult.patternIndex := matchedIndices(
      matchLength - 1.U
    )
  }
  // We don't want to match a pattern longer than the one we were given as an input, so this is the easiest way to avoid that.
  when(matchLength > io.patternData.length) {
    io.matchResult.length := io.patternData.length
    io.matchResult.patternIndex := matchedIndices(
      io.patternData.length - 1.U
    )
  }.otherwise {
    io.matchResult.length := matchLength
  }
}

object singleCyclePatternSearch extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new singleCyclePatternSearch(lz77Config))
}
