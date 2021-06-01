package lz77Decompressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77InputsAndOutputs._
import singleCyclePatternSearch._

class lz77Decompressor(params: lz77Parameters) extends Module {

  // This function is used to determine the length of an incoming encoding.
  def getEncodingLength(encoding: UInt): UInt = {
    val extraPatternLengthCharacters = Wire(Vec(params.additionalPatternLengthCharacters, UInt(params.characterBits.W)))
    val minimalPatternLength = Wire(UInt(params.minEncodingSequenceLengthBits.W))

    // This stores the data in a format we can more easily process.
    minimalPatternLength := encoding >> (params.characterBits * params.additionalPatternLengthCharacters)
    for (index <- 0 until params.additionalPatternLengthCharacters) {
      extraPatternLengthCharacters(index) := encoding >> (params.characterBits * (params.additionalPatternLengthCharacters - 1 - index))
    }

    val outputWire = Wire(UInt(params.patternLengthBits.W))
    when(minimalPatternLength < Fill(params.minEncodingSequenceLengthBits, 1.U)) {
      // The pattern isn't long enough to require multiple characters to encode the full length
      outputWire := minimalPatternLength +& params.minCharactersToEncode.U
    }.otherwise {
      // The pattern is long enough to need extra characters to encode it, so keep reading characters until one isn't the max possible size.
      val charactersToRead = 1.U +& PriorityEncoder(extraPatternLengthCharacters.map(_ =/= params.maxCharacterValue.U))
      // Basically, if the index is less than the charactersToRead value, we bitwise AND it with 1's, and otherwise, we bitwise AND with 0's.
      // Then, we add all those values up and get the total extra character length.
      outputWire := minimalPatternLength +& params.minCharactersToEncode.U +& extraPatternLengthCharacters.zipWithIndex
        .map({ case (value, index) => Fill(params.characterBits, index.U < charactersToRead) & value })
        .reduce(_ +& _)
    }

    outputWire
  }

  // This function is used to determine the encoding index from an incoming encoding.
  def getEncodingIndex(encoding: UInt): UInt = {
    val encodingIndex = Wire(UInt(params.camAddressBits.W))

    encodingIndex := encoding >> (params.additionalPatternLengthCharacters * params.characterBits + params.minEncodingSequenceLengthBits)

    encodingIndex
  }

  val io = IO(new Bundle {
    val in = new decompressorInputs(params)
    val out = Decoupled(new decompressorOutputs(params))
    val finished = Output(Bool())
  })

  // This initializes the outputs of the decompressor.
  io.in.ready := false.B
  io.in.charactersRead := DontCare
  io.out.valid := false.B
  io.out.bits := DontCare
  io.finished := false.B

  // This register is likely the most complicated part of the design, as params.decompressorMaxCharactersOut will determine how many read and write ports it requires.
  val byteHistory = Reg(Vec(params.camCharacters, UInt(params.characterBits.W)))
  // This keeps track of how many characters have been output by the design.
  val charactersInHistory = RegInit(UInt(params.characterCountBits.W), 0.U)

  // This keeps track of the information from the encoding for the state machine's processing.
  val encodingCharacters = Reg(UInt(params.patternLengthBits.W))
  val encodingCharactersProcessed = Reg(UInt(params.patternLengthBits.W))
  val encodingIndex = Reg(UInt(params.camAddressBits.W))

  // This handles the state machine logic and storage
  val numberOfStates = 3
  val waitingForInput :: copyingDataFromHistory :: finished :: Nil =
    Enum(
      numberOfStates
    )
  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waitingForInput)

  switch(state) {
    is(waitingForInput) {
      when(io.in.valid) {
        when(
          io.in.characters(0) =/= params.escapeCharacter.U || ((io.in.characters(0) === params.escapeCharacter.U) && (io.in
            .characters(1) === params.escapeCharacter.U))
        ) {
          // The input is an ordinary character, don't change the state machine, just output the new data and save the new state to the history.
          io.out.valid := true.B
          io.out.bits.length := 1.U
          io.out.bits.characters(0) := io.in.characters(0)
          when(io.out.ready) {
            io.in.ready := true.B
            when(io.in.characters(0) === params.escapeCharacter.U) {
              // The escape character repeats twice when it is a literal character.
              io.in.charactersRead := 2.U
            }.otherwise {
              io.in.charactersRead := 1.U
            }
            byteHistory(charactersInHistory) := io.in.characters(0)
            charactersInHistory := charactersInHistory + 1.U
            when(charactersInHistory >= (params.charactersToCompress - 1).U) {
              // Once the final character is output, the decompression is finished.
              state := finished
            }
          }
        }.otherwise {
          // The input is not an ordinary character, it's an encoding.
          state := copyingDataFromHistory
          encodingCharactersProcessed := 0.U

          // We need to convert the input characters into a single UInt for use with these functions.
          val allInputCharacters = Wire(Vec(params.maxEncodingCharacterWidths, UInt((params.characterBits * params.maxEncodingCharacterWidths).W)))
          for (index <- 0 until params.maxEncodingCharacterWidths) {
            if (index == 0) {
              allInputCharacters(index) := io.in.characters(index)
            } else {
              allInputCharacters(index) := Cat(allInputCharacters(index - 1), io.in.characters(index))
            }
          }
          val encodingLength = getEncodingLength(allInputCharacters(params.maxEncodingCharacterWidths - 1))
          encodingCharacters := encodingLength
          encodingIndex := getEncodingIndex(allInputCharacters(params.maxEncodingCharacterWidths - 1))
          io.in.ready := true.B
          when(encodingLength < params.maxCharactersInMinEncoding.U) {
            io.in.charactersRead := params.minCharactersToEncode.U
          }.elsewhen(encodingLength === params.maxCharactersInMinEncoding.U) {
            io.in.charactersRead := params.minCharactersToEncode.U +& 1.U
          }.otherwise {
            when(encodingLength === params.maxPatternLength.U) {
              io.in.charactersRead := params.maxEncodingCharacterWidths.U
            }.otherwise {
              io.in.charactersRead := 1.U +& params.minCharactersToEncode.U +& ((encodingLength - params.maxCharactersInMinEncoding.U) / params.maxCharacterValue.U)
            }
          }
        }
      }
    }

    is(copyingDataFromHistory) {
      io.out.valid := true.B
      when(encodingCharacters - encodingCharactersProcessed >= params.decompressorMaxCharactersOut.U) {
        io.out.bits.length := params.decompressorMaxCharactersOut.U
      }.otherwise {
        io.out.bits.length := encodingCharacters - encodingCharactersProcessed
      }
      for (index <- 0 until params.decompressorMaxCharactersOut) {
        // I'm pretty sure it's not possible for us to be reading data that is also being overwritten by the current cycle of decompression, but this is
        // where it would be handled if it were possible.
        io.out.bits.characters(index) := byteHistory(encodingIndex + encodingCharactersProcessed + index.U)
      }
      when(io.out.ready) {
        when(encodingCharacters - encodingCharactersProcessed >= params.decompressorMaxCharactersOut.U) {
          encodingCharactersProcessed := encodingCharactersProcessed + params.decompressorMaxCharactersOut.U
          charactersInHistory := charactersInHistory + params.decompressorMaxCharactersOut.U
        }.otherwise {
          encodingCharactersProcessed := encodingCharactersProcessed + (encodingCharacters - encodingCharactersProcessed)
          charactersInHistory := charactersInHistory + (encodingCharacters - encodingCharactersProcessed)
        }
        val oldDataIndex = encodingIndex + encodingCharactersProcessed
        for (index <- 0 until params.decompressorMaxCharactersOut) {
          // This 0.U needs to be added there because Chisel is getting a stack overflow when trying to do some kind of constant propagation.
          byteHistory(charactersInHistory + index.U) := byteHistory(oldDataIndex + index.U) + 0.U
        }
      }

      when(encodingCharactersProcessed >= encodingCharacters) {
        when(charactersInHistory >= params.charactersToCompress.U) {
          state := finished
        }.otherwise {
          state := waitingForInput
        }
      }
    }

    is(finished) {
      io.finished := true.B
    }
  }
}

object lz77Decompressor extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  if (!lz77Config.camHistoryAvailable) {
    println(
      "Error, cam history must be available for lz77Decompressor to work properly"
    )
    sys.exit(1)
  }
  chisel3.Driver
    .execute(Array[String](), () => new lz77Decompressor(lz77Config))
}