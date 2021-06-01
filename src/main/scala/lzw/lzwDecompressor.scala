package lzwDecompressor

import chisel3._
import chisel3.util._
import lzwParameters._

class lzwDecompressor(params: lzwParameters) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(params.maxEncodingWidth.W)))
    val dataInLength = Output(UInt(params.maxEncodingWidthBits.W))
    val out = Decoupled(UInt(params.characterBufferBits.W))
    val dataOutLength = Output(UInt(params.maxCharacterSequenceBits.W))
  })

  // This takes a dictionary entry and gets the character bits from it.
  def getCharacters(dictionaryEntry: UInt): UInt = {
    val dictionaryEntryWire = Wire(UInt(params.dictionaryEntryBits.W))
    dictionaryEntryWire := dictionaryEntry
    dictionaryEntryWire(
      params.dictionaryEntryBits - 1,
      params.dictionaryEntryBits - params.characterBufferBits
    )
  }
  // This takes a dictionary entry and gets the length bits from it.
  def getLength(dictionaryEntry: UInt): UInt = {
    val dictionaryEntryWire = Wire(UInt(params.dictionaryEntryBits.W))
    dictionaryEntryWire := dictionaryEntry
    dictionaryEntryWire(params.maxCharacterSequenceBits - 1, 0)
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

  val dictionary = RegInit(
    VecInit(
      Seq.fill(params.dictionaryItemsMax)(0.U(params.dictionaryEntryBits.W))
    )
  )
  // This keeps track of how many entries are in the dictionary
  val currentDictionaryEntries =
    RegInit(UInt(params.dictionaryCountBits.W), 0.U)

  // This keeps track of the last matching sequence of characters that appeared to help
  // make new dictionary entries.
  val lastSequence = RegInit(UInt(params.characterBufferBits.W), 0.U)
  // This keeps track of how many characters were in the last sequence of characters.
  val lastLength = RegInit(UInt(params.maxCharacterSequenceBits.W), 0.U)

  // This register stores the input so the input and output ready and valid bits can be decoupled.
  val inBitsRegister = RegInit(UInt(params.maxEncodingWidth.W), 0.U)
  // This register stores whether the input bits register is valid or not.
  val inBitsValid = RegInit(Bool(), false.B)

  // This is the wire for the potential new entry to the dictionary so that it can be made sure that it does not
  // already exist in the dictionary.
  val potentialDictionaryEntry = Wire(UInt(params.dictionaryEntryBits.W))
  // This sets the wire to a default value.
  potentialDictionaryEntry := 0.U

  // This sets the default values for the ports.
  io.in.ready := false.B
  io.dataInLength := 0.U
  io.out.valid := false.B
  io.out.bits := 0.U
  io.dataOutLength := 0.U

  when(inBitsRegister < params.characterPossibilities.U) {
    // This is a raw input character, not a sequence of characters.
    io.out.bits := inBitsRegister
    io.dataOutLength := 1.U
  }.otherwise {
    io.out.bits := getCharacters(
      dictionary(inBitsRegister - params.characterPossibilities.U)
    )
    io.dataOutLength := getLength(
      dictionary(inBitsRegister - params.characterPossibilities.U)
    )
  }
  when(inBitsValid) {
    io.out.valid := true.B
    when(io.out.ready) {
      lastLength := io.dataOutLength
      lastSequence := io.out.bits
      // This handles the creation of the next dictionary entry.
      when(
        currentDictionaryEntries < params.dictionaryItemsMax.U && lastLength < params.maxCharacterSequence.U && lastLength > 0.U
      ) {
        potentialDictionaryEntry := makeDictionaryEntry(
          Cat(
            lastSequence,
            ((io.out.bits >> ((io.dataOutLength - 1.U) * params.characterBits.U))(
              params.characterBits - 1,
              0
            ))
          ),
          lastLength + 1.U
        )
        // This makes sure that the new dictionary entry isn't replicating an old dictionary entry.
        when(!dictionary.exists((entry: UInt) => {
          getCharacters(entry) === getCharacters(potentialDictionaryEntry) && getLength(
            entry
          ) === getLength(potentialDictionaryEntry)
        })) {
          currentDictionaryEntries := currentDictionaryEntries + 1.U
          dictionary(currentDictionaryEntries) := potentialDictionaryEntry
        }
      }
    }
  }

  // This controls the input and makes sure it can be desynced from the output.
  when(inBitsValid === false.B || (io.out.valid && io.out.ready)) {
    io.in.ready := true.B
    when(io.in.valid) {
      inBitsRegister := io.in.bits
      inBitsValid := true.B
    }.otherwise {
      inBitsValid := false.B
    }
  }
}

object lzwDecompressor extends App {
  val lzwwSettingsGetter = new getLZWFromCSV()
  chisel3.Driver.execute(Array[String](), () => new lzwDecompressor(lzwwSettingsGetter.getLZWFromCSV("configFiles/lzw.csv")))
}
