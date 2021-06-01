package lzwCompressor

import chisel3._
import chisel3.util._
import lzwParameters._
import lzwInputsAndOutputs._

class lzwCompressor(
    params: lzwParameters
) extends Module {

  // This is the buffer of previously received characters to check against the dictionary.
  val characterBuffer = RegInit(UInt(params.characterBufferBits.W), 0.U)
  val bufferLength = RegInit(UInt(params.maxCharacterSequenceBits.W), 0.U)

  // This takes a dictionary entry and gets the character bits from it.
  def getCharacters(dictionaryEntry: UInt): UInt = {
    val dictionaryEntryWire = Wire(UInt(params.dictionaryEntryBits.W))
    dictionaryEntryWire := dictionaryEntry
    dictionaryEntryWire(
      params.dictionaryEntryBits - 1,
      params.maxCharacterSequenceBits
    )
  }
  // This takes a dictionary entry and gets the length bits from it.
  def getLength(dictionaryEntry: UInt): UInt = {
    val dictionaryEntryWire = Wire(UInt(params.dictionaryEntryBits.W))
    dictionaryEntryWire := dictionaryEntry
    dictionaryEntryWire(
      params.maxCharacterSequenceBits - 1,
      0
    )
  }
  // This takes the three elements of a dictionary entry and adds them together to make a
  // single entry.
  def makeDictionaryEntry(characters: UInt, length: UInt): UInt = {
    val characterWire = Wire(UInt(params.characterBufferBits.W))
    val lengthWire = Wire(UInt(params.maxCharacterSequenceBits.W))
    characterWire := characters
    lengthWire := length
    Cat(characterWire, lengthWire)
  }
  // This function is used to determine if there was a match between a dictionary entry and the
  // character buffer.
  def checkDictionaryMatch(dictionary: UInt): Bool = {
    getCharacters(dictionary) === characterBuffer && getLength(dictionary) === bufferLength
  }

  val io = IO(new Bundle {
    // This tells the lzw compressor to output the remaining characters in the buffer.
    val stop = Input(Bool())
    val in = Flipped(Decoupled(UInt(params.characterBits.W)))
    val out = Decoupled(UInt(params.maxEncodingWidth.W))
    val dataLength = Output(UInt(params.maxEncodingWidthBits.W))
    // This makes an optional statistics port for getting data about the compression.
    val statistics =
      if (params.debugStatistics) Some(new lzwStatistics(params)) else None
  })

  val numberOfStates = 2
  val waiting :: processing :: Nil = Enum(numberOfStates)
  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)

  val dictionary = RegInit(
    VecInit(
      Seq.fill(params.dictionaryItemsMax)(0.U(params.dictionaryEntryBits.W))
    )
  )
  // This keeps track of how many entries are in the dictionary
  val currentDictionaryEntries =
    RegInit(UInt(params.dictionaryCountBits.W), 0.U)

  // This is the last dictionary entry that matched the current entry.
  val dictionaryIndex = Reg(UInt(params.dictionaryAddressBits.W))

  // This stores the next dictionary entry to be added at a delay of one cycle of outputs.
  val nextEntry = RegInit(UInt(params.dictionaryEntryBits.W), 0.U)

  // This sets the default values for the outputs.
  io.out.bits := 0.U
  io.out.valid := false.B
  io.in.ready := false.B
  // Here, the data length can be calculated
  io.dataLength := params.maxEncodingWidth.U - PriorityEncoder(
    Reverse((params.characterPossibilities - 1).U + currentDictionaryEntries)
  )

  switch(state) {
    is(waiting) {
      io.in.ready := true.B
      when(io.in.valid) {
        when(bufferLength > 0.U) {
          state := processing
        }
        characterBuffer := Cat(characterBuffer, io.in.bits)
        bufferLength := bufferLength + 1.U
      }.elsewhen(io.stop) {
        when(bufferLength > 0.U) {
          io.out.valid := true.B
          when(bufferLength === 1.U) {
            io.out.bits := characterBuffer
          }.otherwise {
            io.out.bits := dictionaryIndex +& params.characterPossibilities.U
          }
          when(io.out.ready) {
            bufferLength := 0.U
            characterBuffer := 0.U
          }
        }
      }
    }

    is(processing) {
      when(dictionary.exists(checkDictionaryMatch(_))) {
        when(bufferLength =/= params.maxCharacterSequence.U) {
          // This sets the state to waiting again and sets the last matching dictionary index appropriately.
          state := waiting
          // For some reason, the lastIndexWhere function messes up here, so you need to help it out by doing this pointless math.
          dictionaryIndex := dictionary.lastIndexWhere(checkDictionaryMatch(_)) + 0.U
        }.otherwise {
          // Because a larger dictionary sequence cannot be made, this just outputs the current dictionary sequence.
          io.out.valid := true.B
          io.out.bits := params.characterPossibilities.U +& dictionary
            .lastIndexWhere(checkDictionaryMatch(_))
          when(io.out.ready) {
            state := waiting
            characterBuffer := 0.U
            bufferLength := 0.U
            // No items are being added to the dictionary, so set nextEntry to 0
            nextEntry := 0.U
          }
        }
      }.otherwise {
        io.out.valid := true.B
        when(bufferLength === 2.U) {
          // If the buffer length is 2, the output character is just the raw character value
          io.out.bits := characterBuffer(
            params.characterBits * 2 - 1,
            params.characterBits
          )
        }.otherwise {
          io.out.bits := dictionaryIndex +& params.characterPossibilities.U
        }
        when(io.out.ready) {
          state := waiting
          characterBuffer := characterBuffer(params.characterBits - 1, 0)
          bufferLength := 1.U
          when(currentDictionaryEntries < params.dictionaryItemsMax.U) {
            // Because each new entry to the dictionary is delayed by one output cycle, this ensures that
            // the same entry does not get added to the dictionary twice.
            when(
              !(getCharacters(nextEntry) === characterBuffer && getLength(
                nextEntry
              ) === bufferLength)
            ) {
              nextEntry := makeDictionaryEntry(characterBuffer, bufferLength)
              currentDictionaryEntries := currentDictionaryEntries + 1.U
            }.otherwise {
              nextEntry := 0.U
            }
          }
        }
      }

      when(io.out.valid && io.out.ready) {
        // Add the next entry to the dictionary.
        when(nextEntry =/= 0.U) {
          dictionary(currentDictionaryEntries - 1.U) := nextEntry
        }
      }
    }
  }

  if (params.debugStatistics) {
    io.statistics.get.dictionaryEntries := currentDictionaryEntries
    // This gets the longest length of all of the lengths in the dictionary.
    io.statistics.get.longestSequenceLength := dictionary
      .map(getLength)
      .reduce((a, b) => {
        val longest = Wire(UInt(params.maxCharacterSequenceBits.W))
        when(a > b) {
          longest := a
        }.otherwise { longest := b }
        longest
      })
    // This stores the number of times a character sequence of each length is output.
    val sequenceLengths = RegInit(
      VecInit(
        Seq.fill(params.maxCharacterSequence)(
          0.U(params.debugStatisticsSequenceLengthBits.W)
        )
      )
    )
    for (iteration <- 0 until params.maxCharacterSequence) {
      // The waiting state is avoided here, because that is only necessary for the final sequence when the stop bit is true, and this could
      // cause problems with the counting.
      when(io.out.valid && io.out.ready && state =/= waiting) {
        when(bufferLength === params.maxCharacterSequence.U) {
          sequenceLengths(bufferLength - 1.U) := sequenceLengths(
            bufferLength - 1.U
          ) + 1.U
        }.elsewhen(bufferLength === (iteration + 2).U) {
          sequenceLengths(iteration) := sequenceLengths(iteration) + 1.U
        }
      }
      io.statistics.get.sequenceLengths(iteration) := sequenceLengths(iteration)
    }

  }
}

object lzwCompressor extends App {
  val lzwwSettingsGetter = new getLZWFromCSV()
  chisel3.Driver.execute(
    Array[String](),
    () =>
      new lzwCompressor(lzwwSettingsGetter.getLZWFromCSV("configFiles/lzw.csv"))
  )
}
