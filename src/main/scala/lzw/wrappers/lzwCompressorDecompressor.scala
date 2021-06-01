package lzwCompressorDecompressor

import chisel3._
import chisel3.util._

import lzwCompressor._
import lzwDecompressor._
import lzwParameters._

class lzwCompressorDecompressor(params: lzwParameters) extends Module {
  // This is how many input characters to use for parallel input.
  val inputCharacters = 4096
  // This is how many bits are required to count up to the number of input characters.
  val inputCharacterCountBits = log2Ceil(inputCharacters + 1)
  // This is the number of bits used to show a cycle count.
  val cycleCountBits = log2Ceil(inputCharacters + 1) + 2

  val parallelInput = true
  val parallelOutput = true

  val io = IO(new Bundle {
    val parallelIn = Input(
      Vec(
        if (parallelInput) inputCharacters else 0,
        UInt(params.characterBits.W)
      )
    )
    val in = Flipped(
      Decoupled(UInt(if (parallelInput) 0.W else params.characterBits.W))
    )
    val out =
      Decoupled(UInt(if (parallelOutput) 0.W else params.characterBufferBits.W))
    val parallelOut = Output(
      Vec(
        if (parallelOutput) inputCharacters else 0,
        UInt(params.characterBits.W)
      )
    )
    val completeMatch = Output(UInt(inputCharacterCountBits.W))
    val matchBytes = Output(
      Vec(if (parallelOutput && parallelInput) inputCharacters else 0, Bool())
    )
    val outputBytes = Output(UInt(inputCharacterCountBits.W))
    val compressorCycles =
      if (params.debugStatistics) Some(Output(UInt(cycleCountBits.W))) else None
    val decompressorCycles =
      if (params.debugStatistics) Some(Output(UInt(cycleCountBits.W))) else None
    val finished = Output(Bool())
  })

  val outputBits = RegInit(
    UInt((inputCharacterCountBits + log2Ceil(params.characterBits)).W),
    0.U
  )

  // This stores the current character of the input if using parallel input.
  val inputCharacterCount =
    RegInit(UInt(if (parallelInput) inputCharacterCountBits.W else 0.W), 0.U)
  // This stores the current character of the output if using parallel output.
  val outputCharacterCount =
    RegInit(UInt(if (parallelInput) inputCharacterCountBits.W else 0.W), 0.U)
  // This stores the outputs of the decompressor if using parallel output.
  val outputData = Reg(
    Vec(
      if (parallelOutput) inputCharacters else 0,
      UInt(params.characterBits.W)
    )
  )
  // This shows whether or not the input and output bytes are matching.
  val matchBytes = Wire(
    Vec(if (parallelOutput && parallelInput) inputCharacters else 0, Bool())
  )
  for (index <- 0 until inputCharacters) {
    matchBytes(index) := io.parallelIn(index) === io.parallelOut(index)
  }
  // This shows whether input and output match completely.
  val completeMatch = Wire(UInt(inputCharacterCountBits.W))
  completeMatch := PopCount(matchBytes)
  io.completeMatch <> completeMatch
  io.matchBytes <> matchBytes

  val compress = Module(new lzwCompressor(params))
  val decompress = Module(new lzwDecompressor(params))

  if (parallelInput) {
    compress.io.in.bits := io.parallelIn(inputCharacterCount)
    compress.io.in.valid := inputCharacterCount < inputCharacters.U
    compress.io.stop := inputCharacterCount >= inputCharacters.U
    when(compress.io.in.ready && compress.io.in.valid) {
      inputCharacterCount := inputCharacterCount + 1.U
    }
    io.in.ready := true.B
  } else {
    io.in <> compress.io.in
    compress.io.stop := false.B
  }

  if (parallelOutput) {
    decompress.io.out.ready := true.B
    when(outputCharacterCount < inputCharacters.U) {
      when(decompress.io.out.valid) {
        for (index <- 0 until params.maxCharacterSequence) {
          when(
            params.maxCharacterSequence.U - decompress.io.dataOutLength <= index.U
          ) {
            outputData(
              outputCharacterCount + index.U - (params.maxCharacterSequence.U - decompress.io.dataOutLength)
            ) := decompress.io.out
              .bits(
                params.characterBufferBits - (index * params.characterBits) - 1,
                params.characterBufferBits - ((1 + index) * params.characterBits)
              )
          }
        }
        outputCharacterCount := outputCharacterCount + decompress.io.dataOutLength
      }
    }

    io.parallelOut <> outputData

    io.out.bits := 0.U
    io.out.valid := false.B
    io.finished := outputCharacterCount >= inputCharacters.U
  } else {
    io.out <> decompress.io.out
    io.finished := false.B
  }

  when(compress.io.out.valid && compress.io.out.ready) {
    // This is not an accurate count, but it will be close.
    outputBits := outputBits + compress.io.dataLength
  }

  io.outputBytes := outputBits / params.characterBits.U

  if (params.debugStatistics) {
    // This queue allows the compressor and decompressor to be decoupled.
    val dataQueue = Module(
      new Queue(UInt(params.maxEncodingWidth.W), inputCharacters, true, true)
    )
    compress.io.out <> dataQueue.io.enq
    decompress.io.in <> dataQueue.io.deq
    // This only allows the data queue and decompressor to exchange data once the compressor is finished.
    decompress.io.in.valid := dataQueue.io.deq.valid && compress.io.stop
    dataQueue.io.deq.ready := decompress.io.in.ready && compress.io.stop

    // This counts the cycles of the compressor and decompressor.
    val compressorCycleCount = RegInit(UInt(cycleCountBits.W), 0.U)
    val decompressorCycleCount = RegInit(UInt(cycleCountBits.W), 0.U)

    when(compress.io.in.valid || compress.io.out.valid) {
      compressorCycleCount := compressorCycleCount + 1.U
    }
    when(decompress.io.in.valid && !io.finished) {
      decompressorCycleCount := decompressorCycleCount + 1.U
    }
    io.compressorCycles.get := compressorCycleCount
    io.decompressorCycles.get := decompressorCycleCount
  } else {
    compress.io.out <> decompress.io.in
  }

}

object lzwCompressorDecompressor extends App {
  val settingsGetter = new getLZWFromCSV()
  val lzwwConfig = settingsGetter.getLZWFromCSV("configFiles/lzw.csv")
  chisel3.Driver
    .execute(Array[String](), () => new lzwCompressorDecompressor(lzwwConfig))
}
