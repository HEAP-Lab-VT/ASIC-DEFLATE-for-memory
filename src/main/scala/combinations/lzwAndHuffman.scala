package lzwAndHuffman

import chisel3._
import chisel3.util._
import huffmanCompressor._
import huffmanDecompressor._
import huffmanParameters._
import inputsAndOutputs._
import lzwCompressor._
import lzwDecompressor._
import lzwParameters._
import lzwInputsAndOutputs._

class lzwAndHuffmanStatistics(
    huffmanParams: huffmanParameters,
    lzwParams: lzwParameters
) extends Bundle {
  val huffmanStatistics = new huffmanStatistics(huffmanParams)
  val lzwStatistics = new lzwStatistics(lzwParams)
  val lzwUncompressedBytes = Output(
    UInt(log2Ceil(huffmanParams.characters + 1).W)
  )
  val lzwCompressedBytes = Output(UInt(log2Ceil(huffmanParams.characters + 1).W))
  val huffmanCompressedBytes = Output(
    UInt(log2Ceil(huffmanParams.characters + 1).W)
  )
}

// This very basic version of the combined huffman and LZW compressor and decompressors is just going to use two LZW compressors, one to feed the Huffman character
// frequency counter, and one to feed the huffman encoder itself.
class lzwAndHuffman(lzwParams: lzwParameters, huffmanParams: huffmanParameters)
    extends Module {
  val debugStatistics = lzwParams.debugStatistics
  if(debugStatistics != huffmanParams.debugStatistics){
    println("Error, the debugStatistics setting of huffman and LZW are not the same")
    sys.exit(1)
  }
  // This is how many input characters to use for parallel input.
  val inputCharacters = 4096
  // This is how many bits are required to count up to the number of input characters.
  val inputCharacterCountBits = log2Ceil(inputCharacters + 1)

  val parallelOutput = true

  // val lzwParams = new lzwParameters(
  //   characterBitsParam = 8,
  //   maxCharacterSequenceParam = 64,
  //   dictionaryItemsMaxParam = 768,
  //   debugStatisticsParam = debugStatistics
  // )
  // val huffmanParams = new huffmanParameters(
  //   charactersParam = 4096,
  //   characterBitsParam = 8,
  //   huffmanTreeCharactersParam = 32,
  //   codewordMaxBitsParam = 15,
  //   outputRegistersParam = false,
  //   inputRegisterParam = false,
  //   treeNormalizerInputRegisterParam = false,
  //   characterBufferSizeParam = 1,
  //   treeDesiredMaxDepthParam = 15,
  //   treeDepthInputBitsParam = 8,
  //   characterFrequencyParallelismParam = 1,
  //   compressionParallelismParam = 1,
  //   decompressorCharacterBufferSizeParam = 4,
  //   compressorParallelInputParam = false,
  //   compressorParallelOutputParam = false,
  //   compressorInputRegisterParam = false,
  //   decompressorParallelInputParam = false,
  //   decompressorParallelOutputParam = false,
  //   decompressorInputRegisterParam = false,
  //   variableCompressionParam = true,
  //   debugStatisticsParam = false,
  // )

  if (log2Ceil((1 << lzwParams.characterBits) + lzwParams.dictionaryItemsMax) > huffmanParams.characterBits) {
    println(
      "Error, there must be enough huffman character bits to account for all the lzw dictionary items."
    )
    sys.exit(1)
  }

  val io = IO(new Bundle {
    val start = Input(Bool())
    val parallelIn = Input(Vec(inputCharacters, UInt(lzwParams.characterBits.W)))
    val parallelOut =
      Output(Vec(inputCharacters, UInt(lzwParams.characterBits.W)))
    val completeMatch = Output(UInt(inputCharacterCountBits.W))
    val matchBytes = Output(Vec(inputCharacters, Bool()))
    val outputBytes = Output(UInt(inputCharacterCountBits.W))
    val finished = Output(Bool())
    // This optional port is only created if the debugStatitics are turned on.
    val compressionStatistics =
      if (debugStatistics)
        Some(new lzwAndHuffmanStatistics(huffmanParams, lzwParams))
      else None
  })

  val lzwCompress1 = Module(new lzwCompressor(lzwParams))
  val lzwCompress2 = Module(new lzwCompressor(lzwParams))
  val lzwDecompress = Module(new lzwDecompressor(lzwParams))
  val huffmanCompress = Module(new huffmanCompressor(huffmanParams))
  val huffmanDecompress = Module(new huffmanDecompressor(huffmanParams))

  val outputBits = RegInit(
    UInt((inputCharacterCountBits + log2Ceil(lzwParams.characterBits)).W),
    0.U
  )

  // This stores the current character of the input
  val inputCharacterCount1 = RegInit(UInt(inputCharacterCountBits.W), 0.U)
  val inputCharacterCount2 = RegInit(UInt(inputCharacterCountBits.W), 0.U)
  // This stores the current character of the output
  val outputCharacterCount = RegInit(UInt(inputCharacterCountBits.W), 0.U)
  // This stores the outputs of the decompressor
  val outputData = Reg(Vec(inputCharacters, UInt(lzwParams.characterBits.W)))
  // This shows whether or not the input and output bytes are matching.
  val matchBytes = Wire(Vec(inputCharacters, Bool()))
  for (index <- 0 until inputCharacters) {
    matchBytes(index) := io.parallelIn(index) === io.parallelOut(index)
  }
  // This shows whether input and output match completely.
  val completeMatch = Wire(UInt(inputCharacterCountBits.W))
  completeMatch := PopCount(matchBytes)
  io.completeMatch <> completeMatch
  io.matchBytes <> matchBytes

  // This is the logic that tells the huffman compressor how many characters to compress.
  val variableCompressionCount = RegInit(UInt(inputCharacterCountBits.W), 0.U)
  when(
    huffmanCompress.io.characterFrequencyInputs.ready && huffmanCompress.io.characterFrequencyInputs.valid
  ) {
    variableCompressionCount := variableCompressionCount + 1.U
  }

  // If huffmanParams variableCompression is enabled, this handles the control signals
  if (huffmanParams.variableCompression) {
    // While the inputCharacterCount is still increasing, set the compressionLimit to the max.
    when(inputCharacterCount1 < inputCharacters.U) {
      huffmanCompress.io.characterFrequencyInputs.compressionLimit.get := inputCharacters.U
      huffmanCompress.io
        .compressionInputs(0)
        .compressionLimit
        .get := inputCharacters.U
    }.otherwise {
      huffmanCompress.io.characterFrequencyInputs.compressionLimit.get := variableCompressionCount
      huffmanCompress.io
        .compressionInputs(0)
        .compressionLimit
        .get := variableCompressionCount
    }
  }

  // This is the connection of the LZW inputs.
  lzwCompress1.io.in.bits := io.parallelIn(inputCharacterCount1)
  lzwCompress1.io.in.valid := inputCharacterCount1 < inputCharacters.U
  lzwCompress1.io.stop := inputCharacterCount1 >= inputCharacters.U
  when(lzwCompress1.io.in.ready && lzwCompress1.io.in.valid) {
    inputCharacterCount1 := inputCharacterCount1 + 1.U
  }
  lzwCompress2.io.in.bits := io.parallelIn(inputCharacterCount2)
  lzwCompress2.io.in.valid := inputCharacterCount2 < inputCharacters.U
  lzwCompress2.io.stop := inputCharacterCount2 >= inputCharacters.U
  when(lzwCompress2.io.in.ready && lzwCompress2.io.in.valid) {
    inputCharacterCount2 := inputCharacterCount2 + 1.U
  }

  // This is the connection of the lzw outputs to the huffman compressor inputs
  huffmanCompress.io.start := io.start
  // The LZW inputs will compress to smaller than 4096, so we need to set the remaining inputs to 0 if we want to have the huffman
  // compressor still work (eventually the huffman compressor should take an input that prevents us needing that, but that hasn't been implemented yet.)
  lzwCompress1.io.out.ready := huffmanCompress.io.characterFrequencyInputs.ready
  when(inputCharacterCount1 < inputCharacters.U) {
    huffmanCompress.io.characterFrequencyInputs.dataIn(0) := lzwCompress1.io.out.bits
    huffmanCompress.io.characterFrequencyInputs.valid := lzwCompress1.io.out.valid
  }.otherwise {
    huffmanCompress.io.characterFrequencyInputs.dataIn(0) := 0.U
    huffmanCompress.io.characterFrequencyInputs.valid := true.B
  }
  lzwCompress2.io.out.ready := huffmanCompress.io.compressionInputs(0).ready
  huffmanCompress.io.compressionInputs(0).dataIn(0) := lzwCompress2.io.out.bits
  huffmanCompress.io.compressionInputs(0).valid := lzwCompress2.io.out.valid

  // This is the connection of the huffman compressor outputs to the huffman decompressor inputs.
  huffmanDecompress.io.start := huffmanCompress.io.outputs(0).valid
  huffmanDecompress.io.dataIn(0).bits := huffmanCompress.io
    .outputs(0)
    .dataOut << (huffmanParams.decompressorInputBits - huffmanParams.dictionaryEntryMaxBits)
  huffmanDecompress.io.dataIn(0).valid := huffmanCompress.io.outputs(0).valid
  huffmanCompress.io.outputs(0).ready := huffmanDecompress.io.dataIn(0).ready

  // This is the connection of the huffman compressor outputs to the huffman decompressor inputs.
  huffmanDecompress.io.dataOut(0) <> lzwDecompress.io.in

  // This is the connection of the outputs together.
  lzwDecompress.io.out.ready := true.B
  when(outputCharacterCount < inputCharacters.U) {
    when(lzwDecompress.io.out.valid) {
      // This shifts the decompressor output so that only the characters we care about are present in it.
      val shiftedDecompressOut =
        lzwDecompress.io.out.bits << (lzwParams.characterBits.U * (lzwParams.maxCharacterSequence.U - lzwDecompress.io.dataOutLength))
      for (index <- 0 until lzwParams.maxCharacterSequence) {
        when(index.U < lzwDecompress.io.dataOutLength) {
          when(index.U + outputCharacterCount < inputCharacters.U) {
            outputData(outputCharacterCount + index.U) := shiftedDecompressOut >> (lzwParams.characterBits * (lzwParams.maxCharacterSequence - 1 - index))
          }
        }
      }
      outputCharacterCount := outputCharacterCount + lzwDecompress.io.dataOutLength
    }
  }
  io.parallelOut <> outputData
  io.finished := outputCharacterCount >= inputCharacters.U

  when(
    huffmanCompress.io.outputs(0).valid && huffmanCompress.io.outputs(0).ready
  ) {
    // This is not an accurate count, but it will be close.
    outputBits := outputBits + huffmanCompress.io.outputs(0).dataLength
  }

  io.outputBytes := outputBits / lzwParams.characterBits.U

  // This takes statistics on various numbers in the hardware.
  if (debugStatistics) {
    io.compressionStatistics.get.huffmanStatistics := huffmanCompress.io.statistics.get
    // The LZW compressors should give the same values, so doesn't matter which one we get data from.
    io.compressionStatistics.get.lzwStatistics := lzwCompress1.io.statistics.get
    io.compressionStatistics.get.lzwUncompressedBytes := inputCharacters.U
    // This calculation of lzw compressed size isn't entirely true, for output bit numbers larger than 9, it takes a while to build up to using
    // that number of bits. It starts outputting 9 bits, then outputs 10 bits, then later increases again if available, etc. However,
    // it is a good estimate for comparing against the compression of the huffman compressor.
    io.compressionStatistics.get.lzwCompressedBytes := (variableCompressionCount * huffmanParams.characterBits.U) / lzwParams.characterBits.U
    io.compressionStatistics.get.huffmanCompressedBytes := io.outputBytes
  }
}

object lzwAndHuffman extends App {
  val huffmanSettingsGetter = new getHuffmanFromCSV()
  val lzwwSettingsGetter = new getLZWFromCSV()
  chisel3.Driver.execute(
    Array[String](),
    () =>
      new lzwAndHuffman(
        lzwwSettingsGetter.getLZWFromCSV("configFiles/lzwAndHuffman-LZW.csv"),
        huffmanSettingsGetter.getHuffmanFromCSV(
          "configFiles/lzwAndHuffman-Huffman.csv"
        )
      )
  )
}
