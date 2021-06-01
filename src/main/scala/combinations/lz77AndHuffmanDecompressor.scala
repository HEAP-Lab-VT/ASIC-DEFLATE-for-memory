package lz77AndHuffmanDecompressor

import chisel3._
import chisel3.util._

import lz77Compressor._
import lz77Parameters._
import huffmanCompressor._
import inputsAndOutputs._
import huffmanParameters._

class lz77AndHuffmanDecompressor(lz77Params: lz77Parameters, huffmanParams: huffmanParameters) extends Module {
  // This is how many parallel lz77 compressors to use
  val numberOfLZ77Compressors = 2
  if (lz77Params.charactersToCompress * numberOfLZ77Compressors != huffmanParams.characters) {
    println("Error, the number of lz77 characters to compress times the number of lz77 compressors must be equal to the number of huffman parameters")
    sys.exit(1)
  }
  val charactersPerLZ77 = huffmanParams.characters / numberOfLZ77Compressors

  val io = IO(new Bundle {
    val start = Input(Bool())
    val in = Vec(numberOfLZ77Compressors, Flipped(Decoupled(UInt(lz77Params.characterBits.W))))
    val out = Vec(huffmanParams.compressionParallelism, new compressorOutputData(huffmanParams))
    val finished = Output(Bool())
  })

  // This creates all the lz77 compressors.
  val lz77Compressors = Seq.fill(numberOfLZ77Compressors)(Module(new lz77Compressor(lz77Params)))

  // This creates the huffman compressor.
  val huffmanCompressor = Module(new huffmanCompressor(huffmanParams))

  // Setting default values
  for (index <- 0 until numberOfLZ77Compressors) {
    lz77Compressors(index).io.out.ready := false.B
  }
  huffmanCompressor.io.characterFrequencyInputs.valid := false.B
  huffmanCompressor.io.characterFrequencyInputs.dataIn := DontCare
  for (index <- 0 until huffmanParams.compressionParallelism) {
    huffmanCompressor.io.compressionInputs(index).valid := false.B
    huffmanCompressor.io.compressionInputs(index).dataIn := DontCare
  }

  // Connecting the inputs and outputs of the module to the compressors.
  io.start <> huffmanCompressor.io.start
  io.finished := huffmanCompressor.io.finished && lz77Compressors.map(_.io.finished).reduce(_ && _)
  for (index <- 0 until numberOfLZ77Compressors) {
    io.in(index) <> lz77Compressors(index).io.in
  }
  for (index <- 0 until huffmanParams.compressionParallelism) {
    io.out <> huffmanCompressor.io.outputs
  }

  // This manages the outputs of the lz77 compressors so they can be used by the huffman compressor.
  val lz77Outputs = Reg(Vec(huffmanParams.characters, UInt(lz77Params.characterBits.W)))
  val lz77OutputCounter = RegInit(UInt(huffmanParams.inputCharacterBits.W), 0.U)

  // This iterates through each lz77 compressor, and if the previous one is finished, takes its outputs into the buffer.
  for (index <- 0 until numberOfLZ77Compressors) {
    if (index == 0) {
      // The first compressor goes until it is done outputting
      when(!lz77Compressors(index).finished) {
        lz77Compressors(index).io.out.ready := true.B
        when(lz77Compressors(index).io.out.valid) {
          for (byteIndex <- 0 until lz77Params.maxEncodingCharacterWidths) {
            lz77Outputs(lz77OutputCounter + byteIndex.U) := lz77Compressors(index).io.out.bits.characters(byteIndex)
          }
          lz77OutputCounter := lz77OutputCounter + lz77Compressors(index).io.out.bits.length
        }
      }
    } else {
      // Every compressor after the first starts outputting when the previous compressor is done and it is not finished yet.
      when(!lz77Compressors(index).io.finished && lz77Compressors(index - 1).io.finished) {
        lz77Compressors(index).io.out.ready := true.B
        when(lz77Compressors(index).io.out.valid) {
          for (byteIndex <- 0 until lz77Params.maxEncodingCharacterWidths) {
            lz77Outputs(lz77OutputCounter + byteIndex.U) := lz77Compressors(index).io.out.bits.characters(byteIndex)
          }
          lz77OutputCounter := lz77OutputCounter + lz77Compressors(index).io.out.bits.length
        }
      }
    }
  }

  // This sets up the huffman inputs based on the lz77 outputs.
  if (huffmanParams.variableCompression) {
    when(!lz77Compressors.map(_.io.finished).reduce(_ && _)) {
      // When the compressors aren't all completed, set the max variable compression limit.
      huffmanCompressor.io.characterFrequencyInputs.compressionLimit.get := huffmanParams.characters.U
      for (index <- 0 until huffmanParams.compressionParallelism) {
        huffmanCompressor.io.compressionInputs(index).compressionLimit.get := huffmanParams.characters.U
      }
    }.otherwise {
      // When the compressors are finished, set the real compression limit.
      huffmanCompressor.io.characterFrequencyInputs.compressionLimit.get := lz77OutputCounter
      for (index <- 0 until huffmanParams.compressionParallelism) {
        huffmanCompressor.io.compressionInputs(index).compressionLimit.get := lz77OutputCounter
      }
    }
  }

  // When the huffman compressor hasn't caught up to the lz77 compressors yet, or when the lz77 compressors are finished, huffman inputs should
  // be valid. This sets up the character frequency inputs.
  when(
    huffmanCompressor.io.characterFrequencyInputs.currentByteOut + (huffmanParams.characterFrequencyParallelism - 1).U < lz77OutputCounter || lz77Compressors
      .map(_.io.finished)
      .reduce(_ && _)
  ) {
    huffmanCompressor.io.characterFrequencyInputs.valid := true.B
    for (index <- 0 until huffmanParams.characterFrequencyParallelism) {
      huffmanCompressor.io.characterFrequencyInputs.dataIn(index) := lz77Outputs(huffmanCompressor.io.characterFrequencyInputs.currentByteOut + index.U)
    }
  }

  // When the huffman compressor hasn't caught up to the lz77 compressors yet, or when the lz77 compressors are finished, huffman inputs should
  // be valid. This sets up the huffman compressorOutput inputs.
  for (index <- 0 until huffmanParams.compressionParallelism) {
    when(huffmanCompressor.io.compressionInputs(index).currentByteOut < lz77OutputCounter || lz77Compressors.map(_.io.finished).reduce(_ && _)) {
      huffmanCompressor.io.compressionInputs(0).valid := true.B
      huffmanCompressor.io.compressionInputs(0).dataIn(0) := lz77Outputs(huffmanCompressor.io.characterFrequencyInputs.currentByteOut + index.U)
    }
  }
}

object lz77AndHuffmanDecompressor extends App {
  val lz77SettingsGetter = new getLZ77FromCSV()
  val huffmanSettingsGetter = new getHuffmanFromCSV()
  val lz77Config = lz77SettingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  val huffmanConfig = huffmanSettingsGetter.getHuffmanFromCSV("configFiles/huffmanCompressorDecompressor.csv")
  if (!lz77Config.camHistoryAvailable) {
    println(
      "Error, cam history must be available for lz77Compressor to work properly"
    )
    sys.exit(1)
  }
  chisel3.Driver
    .execute(Array[String](), () => new lz77AndHuffmanDecompressor(lz77Config, huffmanConfig))
}
