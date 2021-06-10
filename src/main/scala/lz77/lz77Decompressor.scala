package lz77Decompressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77InputsAndOutputs._
import lz77.util._
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
    // todo: add parameter for max chars in and don't use maxEncodingCharacterWidths here
    val in = Flipped(DecoupledStream(params.maxEncodingCharacterWidths,
        UInt(params.characterBits.W)))
    val out = DecoupledStream(params.decompressorMaxCharactersOut,
        UInt(params.characterBits.W))
  })
  
  // This initializes the outputs of the decompressor.
  io.in.ready := 0.U
  io.out.valid := 0.U
  io.out.bits := DontCare
  io.out.finished := false.B
  
  // This register is likely the most complicated part of the design, as params.decompressorMaxCharactersOut will determine how many read and write ports it requires.
  // use Mem to avoid FIRRTL stack overflow (chisel3 issue #642)
  // val byteHistory = Reg(Vec(params.camCharacters, UInt(params.characterBits.W)))
  val byteHistory = Mem(params.camCharacters, UInt(params.characterBits.W))
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
  
  // push chars to history
  val newHistoryCount = io.out.valid min io.out.ready
  charactersInHistory := charactersInHistory + newHistoryCount
  for(index <- 0 until io.out.bits.length)
    when(index.U < newHistoryCount) {
      byteHistory(charactersInHistory + index.U) := io.out.bits(index)
    }
  
  switch(state) {
    is(waitingForInput) {
      when(io.in.finished) {
        io.out.finished := true.B
        state := finished
      }.elsewhen(io.in.bits(0) =/= params.escapeCharacter.U ||
          io.in.bits(1) === params.escapeCharacter.U) {
        // indicates which characters are escape characters
        val isesc = io.in.bits.map(_ === params.escapeCharacter.U)
        
        // the number of escapes up to the indexed character
        val pops = WireDefault(VecInit((0 to io.in.bits.length)
          .map(i => PopCount(isesc.take(i)))))
        
        // Find the first possible non-literal
        // The first non-literal has three conditions:
        //  1) current char is escape
        //  2) next char is non-escape
        //  3) (immediately) preceeded by an even number of escapes
        // Note: Since this is the first non-literal, #3 may omit 'immediately'.
        val litcount = PriorityEncoder(isesc
          .zip(isesc.tail :+ false.B)
          .zipWithIndex
          .map{case ((c, n), i) => c && (!n || (i + 1).U === io.in.valid) ||
            i.U === io.in.valid}
          .zip(pops.map(!_(0)))
          .map{case (a, b) => a && b}
          :+ true.B)
        
        // forward literals to output
        io.in.bits.zip(pops.map(_ >> 1)).zipWithIndex
          .foreach{case ((i, p), idx) => io.out.bits(idx.U - p) := i}
        
        // escapes in output up to the indexed character
        val outpops = WireDefault(VecInit((0 to io.out.bits.length)
          .map{i => io.out.bits.take(i)}
          .map(_.map(_ === params.escapeCharacter.U))
          .map(e => PopCount(e))))
        
        // assert ready and valid signals
        io.in.ready := (io.out.ready + outpops(io.out.ready)).min(litcount)
        io.out.valid := litcount - (pops(litcount) >> 1)
      }.otherwise {
        // The input is not an ordinary character, it's an encoding.
        state := copyingDataFromHistory
        encodingCharactersProcessed := 0.U
        
        val encodingLength = getEncodingLength(io.in.bits.asUInt)
        encodingCharacters := encodingLength
        encodingIndex := getEncodingIndex(io.in.bits.asUInt)
        when(encodingLength < params.maxCharactersInMinEncoding.U) {
          io.in.ready := params.minCharactersToEncode.U
        }.elsewhen(encodingLength === params.maxCharactersInMinEncoding.U) {
          io.in.ready := params.minCharactersToEncode.U +& 1.U
        }.otherwise {
          when(encodingLength === params.maxPatternLength.U) {
            io.in.ready := params.maxEncodingCharacterWidths.U
          }.otherwise {
            io.in.ready := 1.U +& params.minCharactersToEncode.U +& ((encodingLength - params.maxCharactersInMinEncoding.U) / params.maxCharacterValue.U)
          }
        }
      }
    }
    
    is(copyingDataFromHistory) {
      io.in.ready := 0.U
      io.out.valid := (encodingCharacters - encodingCharactersProcessed) min
        (params.decompressorMaxCharactersOut.U)
      for (index <- 0 until params.decompressorMaxCharactersOut) {
        // I'm pretty sure it's not possible for us to be reading data that is also being overwritten by the current cycle of decompression, but this is
        // where it would be handled if it were possible.
        io.out.bits(index) :=
          byteHistory(encodingIndex + encodingCharactersProcessed + index.U)
      }
      
      val newEncodingCharactersProcessed =
        encodingCharactersProcessed + newHistoryCount
      
      encodingCharactersProcessed := newEncodingCharactersProcessed
      
      when(newEncodingCharactersProcessed === encodingCharacters) {
        state := waitingForInput
      }
    }
    
    is(finished) {
      io.out.finished := true.B
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
