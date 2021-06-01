package lz77CompressorDecompressor

import chisel3._
import chisel3.util._

import lz77Compressor._
import lz77Decompressor._
import lz77Parameters._

class lz77CompressorDecompressor(params: lz77Parameters) extends Module {
  // This enables counting the cycles of the compressor and decompressor.
  val countCycles = true
  // This is the number of bits needed to count the compressor and decompressor cycles.
  val cycleCountBits = log2Ceil(params.charactersToCompress + 1) + 2

  val io = IO(new Bundle {
    val in =
      Input(Vec(params.charactersToCompress, UInt(params.characterBits.W)))
    val out =
      Output(Vec(params.charactersToCompress, UInt(params.characterBits.W)))
    val matchingBytes = Output(Vec(params.charactersToCompress, Bool()))
    val numberOfMatchingBytes = Output(UInt(params.characterCountBits.W))
    val totalCompressedBytes = Output(UInt(params.characterCountBits.W))
    val compressorCycles =
      if (countCycles) Some(Output(UInt(cycleCountBits.W))) else None
    val decompressorCycles =
      if (countCycles) Some(Output(UInt(cycleCountBits.W))) else None
    val finished = Output(Bool())
  })
  val compressor = Module(new lz77Compressor(params))
  compressor.io.in.valid := false.B
  compressor.io.in.bits := DontCare
  val decompressor = Module(new lz77Decompressor(params))
  decompressor.io.in := DontCare
  decompressor.io.in.valid := false.B

  // These are used to keep track of the number of characters at different stages in the compression and decompression pipelines.
  val compressorInputCounter = RegInit(UInt(params.characterCountBits.W), 0.U)
  val compressorOutputCounter = RegInit(UInt(params.characterCountBits.W), 0.U)
  val decompressorInputCounter = RegInit(UInt(params.characterCountBits.W), 0.U)
  val decompressorOutputCounter =
    RegInit(UInt(params.characterCountBits.W), 0.U)
  // These hold the outputs of the compressor and decompressor.
  val compressorOutput = Reg(
    Vec(params.charactersToCompress * 2, UInt(params.characterBits.W))
  )
  val decompressorOutput = Reg(
    Vec(params.charactersToCompress, UInt(params.characterBits.W))
  )

  when(compressorInputCounter < params.charactersToCompress.U) {
    // Handling the compressor inputs
    compressor.io.in.valid := true.B
    compressor.io.in.bits := io.in(compressorInputCounter)
    when(compressor.io.in.ready) {
      compressorInputCounter := compressorInputCounter + 1.U
    }
  }.otherwise {
    // Handling the decompressor inputs
    decompressor.io.in.valid := true.B
    for (index <- 0 until params.maxEncodingCharacterWidths) {
      decompressor.io.in.characters(index) := compressorOutput(
        decompressorInputCounter + index.U
      )
    }
    when(decompressor.io.in.ready) {
      decompressorInputCounter := decompressorInputCounter + decompressor.io.in.charactersRead
    }
  }

  // Handling the compressor outputs
  compressor.io.out.ready := true.B
  when(compressor.io.out.valid) {
    for (index <- 0 until params.maxEncodingCharacterWidths) {
      when(index.U < compressor.io.out.bits.length) {
        compressorOutput(compressorOutputCounter + index.U) := compressor.io.out.bits
          .characters(index)
      }
    }
    compressorOutputCounter := compressorOutputCounter + compressor.io.out.bits.length
  }

  // Handling the decompressor outputs
  decompressor.io.out.ready := true.B
  when(decompressor.io.out.valid) {
    for (index <- 0 until params.decompressorMaxCharactersOut) {
      when(index.U < decompressor.io.out.bits.length) {
        decompressorOutput(decompressorOutputCounter + index.U) := decompressor.io.out.bits
          .characters(index)
      }
    }
    decompressorOutputCounter := decompressorOutputCounter + decompressor.io.out.bits.length
  }

  // If counting compressor and decompressor cycles is enabled, then this sets up the hardware necessary to count them.
  if(countCycles){
    val compressorCycles = RegInit(UInt(cycleCountBits.W), 0.U)
    val decompressorCycles = RegInit(UInt(cycleCountBits.W), 0.U)

    when(!compressor.io.finished && compressor.io.in.valid){
      compressorCycles := compressorCycles + 1.U
    }
    when(compressor.io.finished && !decompressor.io.finished){
      decompressorCycles := decompressorCycles + 1.U
    }

    io.compressorCycles.get := compressorCycles
    io.decompressorCycles.get := decompressorCycles
  }

  io.out := decompressorOutput
  io.finished := compressor.io.finished && decompressor.io.finished
  io.matchingBytes := io.in
    .zip(io.out)
    .map({ case (inByte, outByte) => inByte === outByte })
  io.numberOfMatchingBytes := PopCount(io.matchingBytes)
  io.totalCompressedBytes := compressorOutputCounter
}

object lz77CompressorDecompressor extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  if (!lz77Config.camHistoryAvailable) {
    println(
      "Error, cam history must be available for lz77Compressor to work properly"
    )
    sys.exit(1)
  }
  chisel3.Driver
    .execute(Array[String](), () => new lz77CompressorDecompressor(lz77Config))
}
