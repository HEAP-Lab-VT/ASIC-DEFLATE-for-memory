package lz77Compressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77InputsAndOutputs._
import singleCyclePatternSearch._

class lz77Compressor(params: lz77Parameters) extends Module {

  // This function is used to determine the length of an outgoing encoding based on the length of the pattern.
  def generateEncodingLength(length: UInt): UInt = {
    val outputWire = Wire(UInt(params.encodingLengthBits.W))
    when(length < params.maxCharactersInMinEncoding.U) {
      outputWire := (params.minEncodingWidth / params.characterBits).U
    }.otherwise {
      when(length === params.maxPatternLength.U) {
        outputWire := params.maxEncodingCharacterWidths.U
      }.otherwise {
        outputWire := (params.minEncodingWidth / params.characterBits).U +& 1.U +& ((length - params.maxCharactersInMinEncoding.U) / params.maxCharacterValue.U)
      }
    }
    outputWire
  }

  // This function is used to generate an outgoing encoding. I can't figure out how to make a function return a Vec,
  // so data is returned as a UInt, then reconstructed after.
  def generateEncoding(index: UInt, length: UInt): UInt = {
    val bitwiseEncoding = Wire(
      Vec(params.maxEncodingWidth, Bool())
    )

    val minEncodingLengthData = Wire(
      UInt(params.minEncodingSequenceLengthBits.W)
    )
    val remainingEncodingLengthData = Wire(
      Vec(
        params.additionalPatternLengthCharacters,
        UInt(params.characterBits.W)
      )
    )
    // The only thing remaining to add to the encoding is the number of characters in the pattern.
    when(
      length < params.maxCharactersInMinEncoding.U
    ) {
      // The minimum number of encoding characters was needed, so set the minEncodingLength value and set all the remaining encoding length data to 0
      minEncodingLengthData := length - params.minCharactersToEncode.U
      for (index <- 0 until params.additionalPatternLengthCharacters) {
        remainingEncodingLengthData(index) := 0.U
      }
    }.otherwise {
      // More than the minimum number of encoding characters was needed, so set the lengths of the characters after the minimum
      minEncodingLengthData := Fill(params.minEncodingSequenceLengthBits, 1.U)
      for (index <- 0 until params.additionalPatternLengthCharacters) {
        when(length > params.maxCharactersInMinEncoding.U + (index * params.extraCharacterLengthIncrease).U) {
          // This character is needed to show the full length of the encoding, so calculate the value of this character.
          when(length - params.maxCharactersInMinEncoding.U - (index * params.extraCharacterLengthIncrease).U <= params.extraCharacterLengthIncrease.U) {
            // This is the final character in the encoding, so it may not need to be the maximum value. Calculate the size based on the remaining
            // pattern length left to show.
            remainingEncodingLengthData(index) := length - params.maxCharactersInMinEncoding.U - (index * params.extraCharacterLengthIncrease).U
          }.otherwise {
            // The remaining data in the encoding is larger than we can show with this character, so just represent it as the maximum value this character
            // can have.
            remainingEncodingLengthData(index) := params.extraCharacterLengthIncrease.U
          }
        }.otherwise {
          // This character wasn't necessary, so set it to 0. In reality, we may not need to set this to any number at all, because this character of the output
          // likely won't be used because the encodingLength output won't tell it to be used.
          remainingEncodingLengthData(index) := 0.U
        }
      }
    }

    bitwiseEncoding := Cat(
      params.escapeCharacter.U(params.characterBits.W),
      Cat(
        !params.escapeCharacter
          .U(params.characterBits.W)
          .asBools()(params.characterBits - 1),
        Cat(
          index,
          Cat(
            minEncodingLengthData,
            0.U(
              (params.maxEncodingWidth - params.characterBits - 1 - params.minEncodingSequenceLengthBits - params.camAddressBits).W
            )
          )
        )
      )
    ).asBools

    // Just need to add remainingEncodingLengthData to the bitwise encoding.
    for (index <- 0 until params.additionalPatternLengthCharacters) {
      for (bitIndex <- 0 until params.characterBits) {
        bitwiseEncoding(params.characterBits * (params.additionalPatternLengthCharacters - 1 - index) + bitIndex) := remainingEncodingLengthData(index)
          .asBools()(bitIndex)
      }
    }

    bitwiseEncoding.asUInt
  }

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(params.characterBits.W)))
    val out = Decoupled(new compressorOutputs(params))
    val finished = Output(Bool())
  })

  // This initializes the outputs of the compressor.
  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := DontCare
  io.finished := false.B

  // This is the buffer that keeps track of the pattern currently being checked.
  val patternBuffer = Reg(
    Vec(params.camMaxPatternLength, UInt(params.characterBits.W))
  )
  val bytesInPatternBuffer =
    RegInit(UInt(params.camCharacterSequenceLengthBits.W), 0.U)

  // This handles the state machine logic and storage
  val numberOfStates = 5
  val loadBytesIntoCAMBuffer :: checkForPattern :: loadBytesIntoCAMHistory :: encodeMatch :: finished :: Nil =
    Enum(
      numberOfStates
    )
  val state = RegInit(UInt(log2Ceil(numberOfStates).W), loadBytesIntoCAMBuffer)

  // This counts how many characters have been read in so far
  val characterCount = RegInit(UInt(params.characterCountBits.W), 0.U)

  // This counts how many bytes have been checked as part of a pattern that was worth encoding.
  val patternLengthCounter = RegInit(UInt(params.patternLengthBits.W), 0.U)
  //This holds the pattern while it is being handled.
  val patternHolder = Reg(new patternMatch(params))

  // This creates the cam and initializes its values
  val patternSearch = Module(new singleCyclePatternSearch(params))
  patternSearch.io.writeData.valid := false.B
  patternSearch.io.writeData.bits := DontCare
  patternSearch.io.patternData.pattern := patternBuffer
  patternSearch.io.patternData.length := bytesInPatternBuffer
  patternSearch.io.patternData.currentCompressorIndex := characterCount - bytesInPatternBuffer

  switch(state) {
    is(loadBytesIntoCAMBuffer) {
      when(characterCount >= params.charactersToCompress.U) {
        state := checkForPattern
      }.otherwise {
        io.in.ready := true.B
        when(io.in.valid) {
          characterCount := characterCount + 1.U
          patternBuffer(bytesInPatternBuffer) := io.in.bits
          bytesInPatternBuffer := bytesInPatternBuffer + 1.U
          when(bytesInPatternBuffer >= (params.camMaxPatternLength - 1).U) {
            state := checkForPattern
          }
        }
      }
    }

    is(checkForPattern) {
      when(
        characterCount >= params.charactersToCompress.U && bytesInPatternBuffer === 0.U
      ) {
        state := finished
      }.otherwise {
        when(
          patternSearch.io.matchResult.length < params.minCharactersToEncode.U
        ) {
          // This outputs the old byte to the output.
          io.out.valid := true.B
          io.out.bits.characters(0) := patternBuffer(0)
          io.out.bits.length := 1.U
          when(patternBuffer(0) === params.escapeCharacter.U) {
            // If the out character happens to be the same as the escape character, send two to make sure they're not mistaken.
            io.out.bits.characters(1) := params.escapeCharacter.U
            io.out.bits.length := 2.U
          }
          when(io.out.ready) {
            io.in.ready := true.B
            // This shifts the bytes of the CAM buffer to their new positions.
            for (index <- 0 until params.camMaxPatternLength - 1) {
              patternBuffer(index) := patternBuffer(index + 1)
            }
            when(io.in.valid) {
              // The input is valid, so stay in this state and add the new byte in.
              patternBuffer(params.camMaxPatternLength - 1) := io.in.bits
              characterCount := characterCount + 1.U
            }.otherwise {
              // The input is not valid, so go to the byte loading state.
              bytesInPatternBuffer := bytesInPatternBuffer - 1.U
              state := loadBytesIntoCAMBuffer
            }
            // Add the removed byte to the byte history
            patternSearch.io.writeData.valid := true.B
            patternSearch.io.writeData.bits := patternBuffer(0)
          }
        }.otherwise {
          // There are enough bytes here to warrant encoding, so we will go to another state to do so
          state := loadBytesIntoCAMHistory
          patternHolder := patternSearch.io.matchResult
          patternLengthCounter := 0.U
        }
      }
    }

    is(loadBytesIntoCAMHistory) {
      // This state is used to load the bytes in the pattern buffer that were matched by the CAM into the CAM history before outputting a match.
      patternSearch.io.writeData.valid := true.B
      patternSearch.io.writeData.bits := patternBuffer(0)
      patternLengthCounter := patternLengthCounter + 1.U
      for (index <- 1 until params.camMaxPatternLength) {
        patternBuffer(index - 1) := patternBuffer(index)
      }
      bytesInPatternBuffer := bytesInPatternBuffer - 1.U
      when(patternLengthCounter >= patternHolder.length - 1.U) {
        state := encodeMatch
        patternLengthCounter := 0.U
      }
    }

    is(encodeMatch) {
      when(patternHolder.length === params.camMaxPatternLength.U) {
        // The pattern is the maximum pattern length, so we need to write some state machine code to handle searching through characters
        // until we find a character that doesn't match.
        when(
          characterCount >= params.charactersToCompress.U
        ) {
          // All remaining data has been matched, so output the resultant pattern and go to the finished state.
          io.out.valid := true.B
          io.out.bits.length := generateEncodingLength(
            patternHolder.length +& patternLengthCounter
          )
          for (index <- 0 until params.maxEncodingCharacterWidths) {
            io.out.bits.characters(index) := generateEncoding(
              patternHolder.patternIndex,
              patternHolder.length +& patternLengthCounter
            ) >> ((params.maxEncodingCharacterWidths - index - 1) * params.characterBits)
          }
          when(io.out.ready) {
            state := finished
          }
        }.otherwise {
          // The pattern hasn't reached the end of available data or a non-matching pattern, so  keep going one character at a time.
          when(io.out.ready) {
            io.in.ready := true.B
            when(io.in.valid) {
              // This writes the new data into the end of the pattern buffer and the old data into the cam history.
              when(patternLengthCounter =/= 0.U) {
                // The state machine just left loadBytesIntoCAMHistory, so we don't have any data in the pattern buffer to be moving out of it.
                patternSearch.io.writeData.valid := true.B
                patternSearch.io.writeData.bits := patternBuffer(0)
              }
              patternBuffer(0) := io.in.bits
              bytesInPatternBuffer := 1.U
              characterCount := characterCount + 1.U

              // This is necessary, because in rare cases when the alignment is just right, you could get the cam history being read before it is written
              val byteToCompareTo = Wire(UInt(params.characterBits.W))
              byteToCompareTo := patternSearch.io.camHistory.get(
                patternHolder.patternIndex +& patternHolder.length +& patternLengthCounter
              )

              when(io.in.bits =/= byteToCompareTo || patternLengthCounter + patternHolder.length >= params.maxPatternLength.U) {
                // The newest byte isn't equal to the next byte in the sequence, or the max sequence length has been reached, so finish
                io.out.valid := true.B
                io.out.bits.length := generateEncodingLength(
                  patternHolder.length +& patternLengthCounter
                )
                for (index <- 0 until params.maxEncodingCharacterWidths) {
                  io.out.bits.characters(index) := generateEncoding(
                    patternHolder.patternIndex,
                    patternHolder.length +& patternLengthCounter
                  ) >> ((params.maxEncodingCharacterWidths - index - 1) * params.characterBits)
                }
                state := loadBytesIntoCAMBuffer
              }.otherwise {
                patternLengthCounter := patternLengthCounter + 1.U
              }
            }
          }
        }

      }.otherwise {
        // The pattern is not the maximum pattern length, so just encode the pattern itself,
        // then go back to the checking for pattern state.
        io.out.valid := true.B
        io.out.bits.length := generateEncodingLength(patternHolder.length)
        for (index <- 0 until params.maxEncodingCharacterWidths) {
          io.out.bits.characters(index) := generateEncoding(
            patternHolder.patternIndex,
            patternHolder.length
          ) >> ((params.maxEncodingCharacterWidths - index - 1) * params.characterBits)
        }
        when(io.out.ready) {
          // Shift the old bytes out of the pattern buffer and change the length.
          state := loadBytesIntoCAMBuffer
        }
      }
    }

    is(finished) {
      io.finished := true.B
    }
  }
}

object lz77Compressor extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  if (!lz77Config.camHistoryAvailable) {
    println(
      "Error, cam history must be available for lz77Compressor to work properly"
    )
    sys.exit(1)
  }
  chisel3.Driver
    .execute(Array[String](), () => new lz77Compressor(lz77Config))
}
