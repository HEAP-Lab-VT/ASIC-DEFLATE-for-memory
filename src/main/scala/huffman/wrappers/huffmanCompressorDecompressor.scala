package huffmanCompressorDecompressor

import chisel3._
import chisel3.util._
import huffmanCompressor._
import huffmanDecompressor._
import inputsAndOutputs._
import huffmanParameters._

class huffmanCompressorDecompressor(params: huffmanParameters) extends Module {
  val parallelInput = true
  val parallelOutput = true
  val variableCompressionParam = true
  if (variableCompressionParam != params.variableCompression) {
    println(
      "Error, variableCompressionParam and params.variableCompressionParam need to be the same"
    )
    sys.exit(1)
  }
  params.compressorParallelInput = parallelInput
  val io =
    IO(new Bundle {
      val start = Input(Bool())
      val dataIn = if (parallelInput) {
        Input(Vec(params.characters, UInt(params.characterBits.W)))
      } else {
        MixedVec(
          new compressorInputData(params, true),
          Vec(
            params.compressionParallelism,
            new compressorInputData(params, false)
          )
        )
      }
      val dataOut = if (parallelOutput) {
        Output(Vec(params.characters, UInt(params.characterBits.W)))
      } else {
        Vec(
          params.compressionParallelism,
          Decoupled(UInt(params.characterBits.W))
        )
      }
      // This is one of the decompressor outputs.
      val currentBit = Output(
        Vec(
          params.compressionParallelism,
          UInt(params.parallelCharactersBitAddressBits.W)
        )
      )
      // This is the total number of bytes that the input was compressed to.
      val outputBytes = Output(UInt(params.inputCharacterBits.W))
      // This gives the limit of the number of bytes to be compressed, if that limit is less than the maximum number of bytes.
      val compressionLimit =
        if (variableCompressionParam)
          Some(Input(UInt(params.inputCharacterBits.W)))
        else None
      val finished = Output(Bool())
    })

  val compressor = Module(new huffmanCompressor(params))
  val decompressor = Module(new huffmanDecompressor(params))

  // This is the total number of bits that the input was compressed to.
  val outputBits = Reg(
    UInt((params.inputCharacterBits + params.characterBits).W)
  )

  // These data structures will help to read out the decompressor data in order
  // into the output register if parallelOutput is set
  val dataOut = Reg(
    Vec(
      if (parallelOutput) params.characters else 0,
      UInt(params.characterBits.W)
    )
  )
  val decompressorCounter = Reg(
    Vec(
      if (parallelOutput) params.compressionParallelism else 0,
      UInt(params.parallelCharactersNumberBits.W)
    )
  )
  val startPrevious = Reg(Bool())
  startPrevious := io.start
  when(startPrevious === false.B && io.start === true.B) {
    outputBits := 0.U
    if (parallelOutput) {
      for (index <- 0 until params.compressionParallelism) {
        decompressorCounter(index) := 0.U
      }
    }
  }.otherwise {
    outputBits := outputBits + compressor.io.outputs
      .map(_.dataLength)
      .reduce(_ +& _)
  }

  io.start <> compressor.io.start
  decompressor.io.start <> compressor.io.outputs(0).valid
  if (parallelInput) {
    compressor.io.characterFrequencyInputs.valid := true.B
    compressor.io.characterFrequencyInputs.dataIn <> io.dataIn
  } else {
    compressor.io.characterFrequencyInputs <> io.dataIn(0)
    compressor.io.compressionInputs <> io.dataIn(1)
  }

  if (variableCompressionParam) {
    for (index <- 0 until params.compressionParallelism) {
      compressor.io
        .compressionInputs(index)
        .compressionLimit
        .get <> io.compressionLimit.get
    }
    compressor.io.characterFrequencyInputs.compressionLimit.get <> io.compressionLimit.get
  }

  for (index <- 0 until params.compressionParallelism) {
    if (parallelInput) {
      compressor.io.compressionInputs(index).valid := true.B
      compressor.io.compressionInputs(index).dataIn <> io.dataIn
    }

    compressor.io.outputs(index).ready := decompressor.io.dataIn(index).ready
    decompressor.io.dataIn(index).valid := compressor.io.outputs(index).valid
    decompressor.io.dataIn(index).bits := compressor.io
      .outputs(index)
      .dataOut << (params.decompressorInputBits - params.dictionaryEntryMaxBits)
    decompressor.io.dataOut(index).ready := true.B
    if (parallelOutput) {
      when(
        decompressor.io
          .dataOut(index)
          .valid && decompressorCounter(index) < params.parallelCharacters.U
      ) {
        dataOut(decompressorCounter(index) * params.compressionParallelism.U + index.U) := decompressor.io.dataOut(index).bits
        decompressorCounter(index) := decompressorCounter(index) + 1.U
      }
    }
  }
  if (parallelOutput) {
    io.dataOut <> dataOut
  } else {
    io.dataOut <> decompressor.io.dataOut
  }
  io.currentBit <> decompressor.io.currentBit
  // check that all the decompressor counters have finished
  io.finished := decompressor.io.finished && compressor.io.finished

  io.outputBytes := outputBits / params.characterBits.U
}

object huffmanCompressorDecompressor extends App {
  val settingsGetter = new getHuffmanFromCSV()
  chisel3.Driver
    .execute(
      Array[String](),
      () =>
        new huffmanCompressorDecompressor(
          settingsGetter.getHuffmanFromCSV("configFiles/huffmanCompressorDecompressor.csv")
        )
    )
}
