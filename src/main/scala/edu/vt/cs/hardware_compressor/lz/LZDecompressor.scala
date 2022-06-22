package edu.vt.cs.hardware_compressor.lz

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.nio.file.Path

class LZDecompressor(params: Parameters) extends Module {
  val io = IO(new StreamBundle(
    params.decompressorCharsIn, UInt(params.characterBits.W),
    params.decompressorCharsOut, UInt(params.characterBits.W)))
  
  // This initializes the outputs of the decompressor.
  io.in.ready := 0.U
  io.out.valid := 0.U
  io.out.bits := DontCare
  io.out.finished := false.B
  
  // Mem avoids FIRRTL stack overflow (chisel3 issue #642)
  // Mem uses verilog array instead of chained muxes
  // TODO: prewrite optimization like compressor
  val camBuffer = Mem(params.camBufSize, UInt(params.characterBits.W))
  // the position in byteHistory of the next byte to write
  val camIndex = RegInit(UInt(params.camBufSize.idxBits.W), 0.U)
  
  // push chars to history
  val newHistoryCount = io.out.valid min io.out.ready
  camIndex := (
    if(params.camBufSize.isPow2) camIndex + newHistoryCount
    else (camIndex +& newHistoryCount) % params.camBufSize.U)
  for(index <- 0 until params.decompressorCharsOut)
    camBuffer(
      if(params.camBufSize.isPow2)
        (camIndex + index.U)(params.camBufSize.idxBits - 1, 0)
      else
        (camIndex + index.U) % params.camBufSize.U
    ) := io.out.bits(index)
  
  
  // This records the encoding header while traversing an encoding
  val matchLength = Reg(UInt((params.extraCharacterLengthIncrease
    max (params.maxCharsInMinEncoding + 1)).valBits.W))
  val matchAddress = Reg(UInt(params.camSize.idxBits.W))
  val matchContinue = Reg(Bool())
  
  // This handles the state machine logic and storage
  val numberOfStates = 3
  val waitingForInput :: copyingDataFromHistory :: finished :: Nil =
    Enum(
      numberOfStates
    )
  val state = RegInit(UInt(numberOfStates.idxBits.W), waitingForInput)
  
  switch(state) {
    is(waitingForInput) {
      when(io.in.finished && io.in.valid === 0.U) {
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
        io.out.valid := (litcount - (pops(litcount) >> 1)) min
          params.decompressorCharsOut.U
        
        io.out.finished := io.in.finished && litcount === io.in.valid &&
          (litcount - (pops(litcount) >> 1)) <=
            params.decompressorCharsOut.U
      }.elsewhen(io.in.valid < params.minEncodingChars.U) {
        // this might be an encoding, but not enough valid input to process it
        io.in.ready := 0.U
        io.out.valid := 0.U
        io.out.finished := io.in.finished
        
        // if in-finished is asserted, then valid must be zero
        // because otherwise, the decompressor would end in an invalid state
      } otherwise {
        // The input is not an ordinary character, it's an encoding.
        state := copyingDataFromHistory
        
        val allInputCharacters = io.in.bits
          .take(params.minEncodingChars)
          .reduce(_ ## _)
        
        matchAddress := allInputCharacters(
          params.minEncodingBits - params.characterBits - 2,
          params.minEncodingLengthBits)
        matchLength := params.minCharsToEncode.U +& allInputCharacters(
          params.minEncodingLengthBits - 1,
          0)
        matchContinue := allInputCharacters(
          params.minEncodingLengthBits - 1,
          0).andR
        
        io.in.ready := params.minEncodingChars.U
        io.out.valid := 0.U
        
        // todo: process part of the encoding this cycle
      }
    }
    
    is(copyingDataFromHistory) {
      // processing an encoding
      
      for(index <- 0 until io.out.bits.length)
        when(matchAddress < (params.camSize - index max 0).U) {
          val bufIdx = matchAddress +& camIndex +&
            (params.camBufSize - params.camSize + index).U
          io.out.bits(index) := camBuffer(
            if(params.camBufSize.isPow2)
              bufIdx(params.camBufSize.idxBits - 1, 0)
            else
              bufIdx % params.camBufSize.idxBits.U)
        } otherwise {
          if(index > 0)
            io.out.bits(index) :=
              VecInit(io.out.bits.take(index))(matchAddress +
                index.U - params.camSize.U)
        }
      
      when(matchContinue) {
        // the current character is part of an encoding length
        
        // the maximum input characters to consume with these params
        val maxInChars = io.in.bits.length min
          ((io.out.bits.length - 1) / params.extraCharacterLengthIncrease + 1)
        
        // Loop through input and detect conditions that stop parsing.
        // Any of the following conditions stop parsing:
        //  1) non-full character (end of encoding)
        //  2) end of valid input
        //  3) output not ready
        // Loop backward so first (most recent) stop condition is used.
        // matchLength is residual length from already processed characters
        for(index <- (0 to maxInChars).reverse) {
          // valid out chars up to (but not including) current index
          val whole = matchLength +&
            (index * params.extraCharacterLengthIncrease).U
          if(index != maxInChars)
          when(!io.in.bits(index).andR) {
            // current character is incomplete
            // consume current, and de-assert continue
            val outvalid = whole +& io.in.bits(index)
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
            io.out.finished := io.in.finished &&
              io.in.valid === (index + 1).U &&
              outvalid <= io.out.bits.length.U
          }
          when(index.U === io.in.valid || (index == maxInChars).B) {
            // this marks the beginning of valid data
            // reset to defaults because previous assertions are invalid
            io.out.valid := whole min io.out.bits.length.U
            matchLength := 0.U
            matchContinue := true.B
            state := copyingDataFromHistory
            // io.out.finished := false.B
          }
          when(io.out.ready < whole) {
            // receiver is not ready for this block
            // mark where push ends on next block
            io.in.ready := index.U
            matchLength := whole - io.out.ready
            matchContinue := true.B
            state := copyingDataFromHistory
          } otherwise {if(index == maxInChars) io.in.ready := maxInChars.U}
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

object LZDecompressor extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/lz.csv"))
  new chisel3.stage.ChiselStage()
    .emitVerilog(new LZDecompressor(params), args)
}
