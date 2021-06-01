package lz77AndHuffman

import chisel3._
import chisel3.util._

import lz77Compressor._
import lz77Decompressor._
import lz77Parameters._
import huffmanCompressorDecompressor._
import huffmanParameters._

class lz77AndHuffman(lz77Params: lz77Parameters, huffmanParams: huffmanParameters) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(lz77Params.charactersToCompress, UInt(lz77Params.characterBits.W)))
    val out = Output(Vec(lz77Params.charactersToCompress, UInt(lz77Params.characterBits.W)))
    val matchingBytes = Output(Vec(lz77Params.charactersToCompress, Bool()))
    val numberOfMatchingBytes = Output(UInt(lz77Params.characterCountBits.W))
    val lz77CompressedBytes = Output(UInt(lz77Params.characterCountBits.W))
    val huffmanCompressedBytes = Output(UInt(huffmanParams.inputCharacterBits.W))
    val finished = Output(Bool())
  })

  // These are used to keep track of the number of characters at different stages in the compression and decompression pipelines.
  val compressorInputCounter = RegInit(UInt(lz77Params.characterCountBits.W), 0.U)
  val compressorOutputCounter = RegInit(UInt(lz77Params.characterCountBits.W), 0.U)
  val decompressorInputCounter = RegInit(UInt(lz77Params.characterCountBits.W), 0.U)
  val decompressorOutputCounter = RegInit(UInt(lz77Params.characterCountBits.W), 0.U)
  // These hold the outputs of the compressor and decompressor.
  val compressorOutput = Reg(Vec(lz77Params.charactersToCompress * 2, UInt(lz77Params.characterBits.W)))
  val decompressorInput = Wire(Vec(lz77Params.charactersToCompress, UInt(lz77Params.characterBits.W)))
  val decompressorOutput = Reg(Vec(lz77Params.charactersToCompress, UInt(lz77Params.characterBits.W)))

  // This hooks up the inputs and outputs of the compressor and decompressor in the default case.
  val compressor = Module(new lz77Compressor(lz77Params))
  compressor.io.in.valid := false.B
  compressor.io.in.bits := DontCare
  val decompressor = Module(new lz77Decompressor(lz77Params))
  decompressor.io.in := DontCare
  decompressor.io.in.valid := false.B
  val huffmanHardware = Module(new huffmanCompressorDecompressor(huffmanParams))
  if (huffmanParams.variableCompression) {
    huffmanHardware.io.compressionLimit.get := compressorOutputCounter
  }
  huffmanHardware.io.dataOut <> decompressorInput
  io.huffmanCompressedBytes <> huffmanHardware.io.outputBytes

  when(compressorInputCounter < lz77Params.charactersToCompress.U) {
    // Handling the LZ77 compressor inputs
    compressor.io.in.valid := true.B
    compressor.io.in.bits := io.in(compressorInputCounter)
    when(compressor.io.in.ready) {
      compressorInputCounter := compressorInputCounter + 1.U
    }
    // Making sure the huffman hardware doesn't start working too early.
    huffmanHardware.io.start := false.B
  }.otherwise {
    huffmanHardware.io.start := true.B
  }

  // This makes sure that the huffman hardware gets the actual data instead of the LZ77 output if the LZ77 output is too big.
  when(compressorOutputCounter > huffmanParams.characters.U) {
    huffmanHardware.io.dataIn <> io.in
  }.otherwise {
    for (index <- 0 until huffmanParams.characters) {
      huffmanHardware.io.dataIn(index) := compressorOutput(index)
    }
  }

  // Handling the LZ77 compressor outputs
  compressor.io.out.ready := true.B
  when(compressor.io.out.valid) {
    for (index <- 0 until lz77Params.maxEncodingCharacterWidths) {
      when(index.U < compressor.io.out.bits.length) {
        compressorOutput(compressorOutputCounter + index.U) := compressor.io.out.bits.characters(index)
      }
    }
    compressorOutputCounter := compressorOutputCounter + compressor.io.out.bits.length
  }

  // This counter is used to delay the output from the huffman hardware from going into the LZ77 compressor and decompressor
  val delayCycles = 4196
  val delayCounter = RegInit(UInt(log2Ceil(delayCycles + 1).W), 0.U)

  when(compressorInputCounter >= lz77Params.charactersToCompress.U) {
    when(huffmanHardware.io.finished && delayCounter >= delayCycles.U) {
      // Handling the LZ77 decompressor inputs
      decompressor.io.in.valid := true.B
      for (index <- 0 until lz77Params.maxEncodingCharacterWidths) {
        decompressor.io.in.characters(index) := decompressorInput(decompressorInputCounter + index.U)
      }
      when(decompressor.io.in.ready) {
        decompressorInputCounter := decompressorInputCounter + decompressor.io.in.charactersRead
      }
    }.elsewhen(delayCounter < delayCycles.U) {
      // Increment the delay counter to be sure huffman has had enough time to compute before reading.
      delayCounter := delayCounter + 1.U
    }
  }

  // Handling the LZ77 decompressor outputs
  decompressor.io.out.ready := true.B
  when(decompressor.io.out.valid) {
    for (index <- 0 until lz77Params.decompressorMaxCharactersOut) {
      when(index.U < decompressor.io.out.bits.length) {
        decompressorOutput(decompressorOutputCounter + index.U) := decompressor.io.out.bits.characters(index)
      }
    }
    decompressorOutputCounter := decompressorOutputCounter + decompressor.io.out.bits.length
  }

  val lastHuffmanHardwareFinished = RegNext(huffmanHardware.io.finished)
  when(compressorOutputCounter > lz77Params.charactersToCompress.U) {
    // When the LZ77 output is larger than 4KB, use the huffman output and finished signals instead, but not the LZ77 decompressor finished signal.
    io.out := huffmanHardware.io.dataOut
    io.finished := compressor.io.finished && huffmanHardware.io.finished && !lastHuffmanHardwareFinished
    if (huffmanParams.variableCompression) {
      huffmanHardware.io.compressionLimit.get := huffmanParams.characters.U
    }
  }.otherwise {
    // When the LZ77 output is smaller than 4KB, use the LZ77 output.
    io.out := decompressorOutput
    io.finished := compressor.io.finished && decompressor.io.finished && huffmanHardware.io.finished
  }
  io.matchingBytes := io.in.zip(io.out).map({ case (inByte, outByte) => inByte === outByte })
  io.numberOfMatchingBytes := PopCount(io.matchingBytes)
  io.lz77CompressedBytes := compressorOutputCounter
}

object lz77AndHuffman extends App {
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
    .execute(Array[String](), () => new lz77AndHuffman(lz77Config, huffmanConfig))
}
