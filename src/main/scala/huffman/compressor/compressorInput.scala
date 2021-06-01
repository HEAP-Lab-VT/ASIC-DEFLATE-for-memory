package compressorInput

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class compressorInput(params: huffmanParameters, isCharacterFrequencyInput: Boolean) extends Module {
  // This is used to differentiate between an input for the characterFrequencyCounter and the compressorOutput. I know it's suboptimal, 
  // but it's what we've got right now.
  val outputCharacterBufferSize = if(isCharacterFrequencyInput){
    params.characterFrequencyParallelism
  } else{
    1
  }
  val dataInCharacters = if (params.compressorParallelInput) {
    params.characters
  } else {
    outputCharacterBufferSize
  }

  val io = IO(new Bundle {
    val start = Input(Bool())
    val input =
      new compressorInputData(params, isCharacterFrequencyInput)
    val currentByte = Input(UInt(params.inputCharacterAddressBits.W))
    val dataOut =
      Decoupled(Vec(outputCharacterBufferSize, UInt(params.characterBits.W)))
    val compressionLimit = if(params.variableCompression) Some(Output(UInt(params.inputCharacterBits.W))) else None
  })

  if(params.variableCompression){
    io.compressionLimit.get := io.input.compressionLimit.get
  }

  io.dataOut.ready <> io.input.ready

  if (params.compressorParallelInput) {
    io.dataOut.valid := true.B
    if (params.compressorInputRegister) {
      val inputData = Reg(Vec(dataInCharacters, UInt(params.characterBits.W)))

      when(io.start) {
        inputData := io.input.dataIn
      }

      for (index <- 0 until outputCharacterBufferSize) {
        when(io.currentByte + index.U > dataInCharacters.U) {
          io.dataOut.bits(index) := 0.U
        }.otherwise {
          io.dataOut.bits(index) := inputData(io.currentByte + index.U)
        }
      }
    } else {
      for (index <- 0 until outputCharacterBufferSize) {
        when(io.currentByte + index.U > dataInCharacters.U) {
          io.dataOut.bits(index) := 0.U
        }.otherwise {
          io.dataOut.bits(index) := io.input.dataIn(io.currentByte + index.U)
        }
      }

    }
    io.input.currentByteOut := 0.U
  } else {
    io.dataOut.valid <> io.input.valid 
    io.dataOut.bits := io.input.dataIn
    io.input.currentByteOut := io.currentByte
  }
}

object compressorInput extends App {
  // chisel3.Driver.execute(Array[String](), () => new compressorInput)
}
