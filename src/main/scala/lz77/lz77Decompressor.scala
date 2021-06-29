package lz77Decompressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77.util._

class lz77Decompressor(params: lz77Parameters) extends Module {
  
  // val io = IO(new Bundle {
  // // todo: add parameter for max chars in and don't use maxEncodingCharacterWidths here
  // val in = Flipped(DecoupledStream(params.maxEncodingCharacterWidths,
  //     UInt(params.characterBits.W)))
  // val out = DecoupledStream(params.decompressorMaxCharactersOut,
  //     UInt(params.characterBits.W))
  // })
  
  val io = IO(new StreamBundle(
    params.maxEncodingCharacterWidths, UInt(params.characterBits.W),
    params.decompressorMaxCharactersOut, UInt(params.characterBits.W)))
  
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
  val byteHistoryIndex = RegInit(UInt(params.camAddressBits.W), 0.U)
  
  // push chars to history
  val newHistoryCount = io.out.valid min io.out.ready
  byteHistoryIndex := (
    if(params.camSizePow2) byteHistoryIndex + newHistoryCount
    else (byteHistoryIndex +& newHistoryCount) % params.camAddressBits.U)
  for(index <- 0 until io.out.bits.length)
    when(index.U < newHistoryCount) {
      byteHistory(
        if(params.camSizePow2)
          (byteHistoryIndex + index.U)(params.camAddressBits - 1, 0)
        else
          (byteHistoryIndex + index.U) % params.camAddressBits.U
      ) := io.out.bits(index)
    }
  
  
  // This keeps track of the information from the encoding for the state machine's processing.
  val matchLength = Reg(UInt(log2Ceil(params.extraCharacterLengthIncrease
    max (params.maxCharactersInMinEncoding + 2)).W))
  val matchAddress = Reg(UInt(params.camAddressBits.W))
  val matchContinue = Reg(Bool())
  
  // This handles the state machine logic and storage
  val numberOfStates = 3
  val waitingForInput :: copyingDataFromHistory :: finished :: Nil =
    Enum(
      numberOfStates
    )
  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waitingForInput)
  
  switch(state) {
    is(waitingForInput) {
      when(io.in.finished) {
        io.out.finished := true.B
        state := finished
      }.elsewhen(io.in.bits(0) =/= params.escapeCharacter.U ||
          io.in.bits(1) === params.escapeCharacter.U) {
        // indicates which characters are escape characters
        val isesc = io.in.bits.map(_ === params.escapeCharacter.U)
        
        // the number of escapes preceeding the indexed character
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
        
        // for a given out-index, what is the corresponding in-index
        val out_to_in_index = Wire(Vec(io.out.bits.length + 1,
          UInt(log2Ceil(io.in.bits.length + 2).W)))
        out_to_in_index.foreach(_ := (io.in.bits.length+1).U)
        pops.map(_ >> 1).zipWithIndex
          .foreach{case (o, i) => out_to_in_index(i.U - o) := i.U}
        
        // forward literals to output
        io.out.bits.zip(out_to_in_index)
          .foreach{case (o, i) => o := io.in.bits(i)}
        
        // assert ready and valid signals
        io.in.ready := out_to_in_index(io.out.ready).min(litcount)
        io.out.valid := litcount - (pops(litcount) >> 1)
      }.elsewhen(io.in.valid < params.minCharactersToEncode.U) {
        // this might be an encoding, but not enough valid input to process it
        // todo: buffer input in this case
        io.in.ready := 0.U
        io.out.valid := 0.U
      } otherwise {
        // The input is not an ordinary character, it's an encoding.
        state := copyingDataFromHistory
        
        val allInputCharacters = io.in.bits
          .take(params.minCharactersToEncode)
          .reduce(_ ## _)
        
        matchAddress := allInputCharacters(
          params.minEncodingWidth - params.characterBits - 2,
          params.minEncodingSequenceLengthBits)
        matchLength := params.minCharactersToEncode.U +& allInputCharacters(
          params.minEncodingSequenceLengthBits - 1,
          0)
        matchContinue := allInputCharacters(
          params.minEncodingSequenceLengthBits - 1,
          0).andR
        
        io.in.ready := params.minCharactersToEncode.U
        io.out.valid := 0.U
        
        // todo: process part of the encoding this cycle
      }
    }
    
    is(copyingDataFromHistory) {
      // processing an encoding
      
      for(index <- 0 until io.out.bits.length)
        when(matchAddress < (params.camCharacters - index).U) {
          io.out.bits(index) := byteHistory(matchAddress + byteHistoryIndex + (
            if(params.camSizePow2) index.U(params.camAddressBits.W)
            else Mux((params.camCharacters - index).U - matchAddress >
              byteHistoryIndex, index.U, (params.camCharacters - index).U)))
        } otherwise {
          if(index > 0)
            io.out.bits(index) :=
              VecInit(io.out.bits.take(index))(matchAddress -
                (params.camCharacters - index).U)
        }
      
      when(matchContinue) {
        // the current character is part of an encoding length
        
        // the maximum input characters to consume w/ these params
        val maxInChars = io.in.bits.length min
          (io.out.bits.length / params.extraCharacterLengthIncrease + 1)
        // valid out chars up to (but not including) current index
        var whole = matchLength +&
          (maxInChars * params.extraCharacterLengthIncrease).U
        // set defaults for valid, ready, and length
        io.out.valid := whole min io.out.bits.length.U
        io.in.ready := maxInChars.U
        matchLength := 0.U
        when(io.out.ready < whole) {
          matchLength := whole - io.out.ready
        }
        for(index <- 0 until maxInChars reverse) {
          // update whole for current index
          whole = matchLength +&
            (index * params.extraCharacterLengthIncrease).U
          when(!io.in.bits(index).andR) {
            // current character is incomplete
            // consume current, and de-assert continue
            var outvalid = whole +& io.in.bits(index)
            io.out.valid := outvalid min io.out.bits.length.U
            io.in.ready := (index + 1).U
            matchLength := outvalid - io.out.ready
            matchContinue := false.B
            when(io.out.ready >= outvalid) {
              // encoding completely pushed
              // finish processing encoding
              state := waitingForInput
              matchLength := DontCare
              matchContinue := DontCare
            }
          }
          when(index.U === io.in.valid) {
            // this marks the beginning of valid data
            // reset to defaults because previous assertions are invalid
            io.out.valid := whole min io.out.bits.length.U
            matchLength := 0.U
            matchContinue := true.B
            state := copyingDataFromHistory
          }
          when(io.out.ready < whole) {
            // receiver is not ready for this block
            // mark where push ends on next block
            io.in.ready := index.U
            matchLength := whole - io.out.ready
            matchContinue := true.B
            state := copyingDataFromHistory
          }
        }
      } otherwise {
        // the encoding is already consumed, push remaining matchLength
        io.out.valid := matchLength min io.out.bits.length.U
        io.in.ready := 0.U
        matchLength := matchLength - io.out.ready
        when(io.out.ready >= matchLength) {
          state := waitingForInput
          matchLength := DontCare
          matchContinue := DontCare
        }
      }
    }
    
    is(finished) {
      io.out.finished := true.B
    }
  }
}

object lz77Decompressor extends App {
  val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new lz77Decompressor(params), args)
}
