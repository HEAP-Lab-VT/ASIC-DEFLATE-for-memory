package patternSearch

import chisel3._
import chisel3.util._
import lz77Parameters._
import cam._
import lz77InputsAndOutputs._

class patternSearch(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    val writeData = Flipped(Decoupled(UInt(params.characterBits.W)))
    // This input allows for search values to be requested from the CAM.
    val patternData = Flipped(Decoupled(new searchInputs(params)))

    // This tells how many bytes were in the longest match and where it was in the CAM.
    val matchResult = Decoupled(new patternMatch(params))
  })

  // This handles default states for the output signals
  io.matchResult.bits := DontCare
  io.matchResult.valid := false.B
  io.writeData.ready := false.B
  io.patternData.ready := false.B

  // This creates the cam and initializes its values
  val cam = Module(new cam(params))
  cam.io.writeData.bits := DontCare
  cam.io.writeData.valid := false.B
  cam.io.searchData.bits := DontCare
  cam.io.searchData.valid := false.B

  // This stores the data about the pattern once there is a valid pattern input available.
  val pattern = Reg(Vec(params.camMaxPatternLength, UInt(params.characterBits.W)))
  val length = Reg(UInt(params.camCharacterSequenceLengthBits.W))

  // This stores the data about the current state of the matching process like how many bytes in a row have matched.
  val matchedPatternLength = Reg(UInt(params.camCharacterSequenceLengthBits.W))
  val patternMatchVec = Reg(Vec(params.camCharacters, Bool()))

  // This handles the state machine logic and storage
  val numberOfStates = 2
  val waiting :: checkingPatterns :: Nil = Enum(numberOfStates)
  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)

  switch(state) {
    is(waiting) {
      // Data can only be written in the waiting phase, as we don't want to change the data as it's being searched through.
      io.writeData <> cam.io.writeData
      io.patternData.ready := true.B
      when(io.patternData.valid && io.patternData.bits.length > 0.U) {
        // Transition to the checking patterns state, lock in the pattern data to a register, and search through the pattern.
        state := checkingPatterns
        pattern := io.patternData.bits.pattern
        length := io.patternData.bits.length
        matchedPatternLength := 0.U
        patternMatchVec := VecInit(Seq.fill(params.camCharacters)(false.B))
      }
    }

    is(checkingPatterns) {
      when(matchedPatternLength === 0.U) {
        // This is the starting state for the pattern checking.
        cam.io.searchData.valid := true.B
        cam.io.searchData.bits := pattern(matchedPatternLength)
        when(cam.io.searchData.ready) {
          when(cam.io.matches.asUInt.orR) {
            // This triggers when there is at least one match in the cam output.
            patternMatchVec := cam.io.matches
            matchedPatternLength := matchedPatternLength + 1.U
          }.otherwise {
            io.matchResult.bits.length := 0.U
            io.matchResult.valid := true.B
            when(io.matchResult.ready) {
              state := waiting
            }
          }
        }
      }.elsewhen(matchedPatternLength === params.camMaxPatternLength.U) {
        io.matchResult.bits.length := params.camMaxPatternLength.U
        io.matchResult.bits.patternIndex := patternMatchVec.indexWhere((patternMatched:Bool) => {patternMatched === true.B})
        io.matchResult.valid := true.B
        when(io.matchResult.ready) {
          state := waiting
        }
      }.otherwise {
        cam.io.searchData.valid := true.B
        cam.io.searchData.bits := pattern(matchedPatternLength)
        when(cam.io.searchData.ready) {
            // This tests making a new pattern that can be used to check if there are any matches remaining when extending the pattern by one character.
          val newMatchedPattern = (matchedPatternLength.asUInt & (cam.io.matches.asUInt >> matchedPatternLength)).asBools
          when(matchedPatternLength.orR) {
            // When the cam is ready to do a search, if the search still results in a match, continue.
            matchedPatternLength := matchedPatternLength + 1.U
            patternMatchVec := newMatchedPattern
          }.otherwise {
            // The match will no longer continue, so return to waiting.
            io.matchResult.bits.length := matchedPatternLength
            io.matchResult.bits.patternIndex := patternMatchVec.indexWhere((patternMatched:Bool) => {patternMatched === true.B})
            io.matchResult.valid := true.B
            when(io.matchResult.ready) {
              state := waiting
            }
          }
        }
      }
    }
  }
}

object patternSearch extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new patternSearch(lz77Config))
}
