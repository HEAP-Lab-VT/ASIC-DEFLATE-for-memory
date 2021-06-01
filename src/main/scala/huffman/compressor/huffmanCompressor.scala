// See README.md for license details.

package huffmanCompressor

import chisel3._
import chisel3.util._
import treeNormalizer._
import sort._
import codewordGenerator._
import compressorOutput._
import treeDepthCounter._
import treeGenerator._
import characterFrequencyModule._
import compressorInput._
import inputsAndOutputs._
import huffmanParameters._

/**
  * Compute huffmanCompressor using subtraction method.
  * Subtracts the smaller from the larger until register y is zero.
  * value in register x is then the huffmanCompressor
  */
class huffmanCompressor(params: huffmanParameters) extends Module {
  val sortMuxBits = params.inputCharacterAddressBits
  val dataOutBits =
    params.codewordLengthMaxBits + params.codewordMaxBits + params.characterRepresentationBits
  val dataLengthBits = log2Ceil(dataOutBits)
  val outputCharacters = if (params.compressorParallelOutput) {
    (1 << sortMuxBits) + params.huffmanTreeCharacters * (params.codewordLengthMaxBits + params.codewordMaxBits + params.characterRepresentationBits) / params.characterBits
  } else {
    1
  }
  val codewordInBits = params.codewordMaxBits + params.characterBits
  val possibleCharacters = 1 << params.characterBits
  val depthCountsInNumber = params.codewordMaxBits + 1
  val nodesInBits = params.characterBits + 1
  val characterInBits = params.characterBits + 1
  val inputBytes = 1 << sortMuxBits
  val dataInCharacters = if (params.compressorParallelInput) {
    1 << sortMuxBits
  } else {
    params.characterFrequencyParallelism
  }

  val io =
    IO(new Bundle {
      val start = Input(Bool())
      val characterFrequencyInputs = new compressorInputData(params, true)
      val compressionInputs = Vec(
        params.compressionParallelism,
        new compressorInputData(params, false)
      )
      val outputs =
        Vec(params.compressionParallelism, new compressorOutputData(params))
      val finished = Output(Bool())
      val statistics =
        if (params.debugStatistics) Some(new huffmanStatistics(params))
        else None
    })

  // Setting up modules
  val cfm = Module(new characterFrequencyModule(params))
  val tg = Module(new treeGenerator(params))
  val tdc = Module(new treeDepthCounter(params))
  val sltg = Module(new sort(params))
  val tn = Module(
    new treeNormalizer(
      params.characterBits,
      sortMuxBits,
      params.huffmanTreeCharacters,
      params.treeDepthInputBits,
      params.treeNormalizerInputRegister,
      params.treeDesiredMaxDepth
    )
  )
  val cg = Module(new codewordGenerator(params))
  val co = Module(new compressorOutput(params))

  // Generating the start signals
  val previousStart = RegNext(io.start)
  val cfmPreviousFinished = RegNext(cfm.io.finished)
  val tgPreviousFinished = RegNext(tg.io.finished)
  val tdcPreviousFinished = RegNext(tdc.io.finished)
  val sltgPreviousFinished = RegNext(sltg.io.finished)
  val tnPreviousFinished = RegNext(tn.io.finished)
  val cgPreviousFinished = RegNext(cg.io.finished)
  val coPreviousFinished = RegNext(co.io.finished)
  cfm.io.start := io.start && ~previousStart
  tg.io.start := cfm.io.finished && ~cfmPreviousFinished
  tdc.io.start := tg.io.finished && ~tgPreviousFinished
  sltg.io.start := tdc.io.finished && ~tdcPreviousFinished
  tn.io.start := sltg.io.finished && ~sltgPreviousFinished
  cg.io.start := tn.io.finished && ~tnPreviousFinished
  co.io.start := cg.io.finished && ~cgPreviousFinished
  io.finished := cfm.io.finished && cfmPreviousFinished && tg.io.finished && tgPreviousFinished && tdc.io.finished && tdcPreviousFinished && sltg.io.finished && sltgPreviousFinished && tn.io.finished && tnPreviousFinished && cg.io.finished && cgPreviousFinished && co.io.finished && coPreviousFinished

  // Connecting data inputs and outputs between modules
  io.characterFrequencyInputs <> cfm.io.input
  for (index <- 0 until params.compressionParallelism) {
    io.compressionInputs(index) <> co.io.dataIn(index)
  }
  tg.io.inputs <> cfm.io.outputs
  tdc.io.inputs <> tg.io.outputs
  sltg.io.inputs <> tdc.io.outputs
  tn.io.inputs <> sltg.io.outputs
  cg.io.inputs <> tn.io.outputs
  co.io.inputs <> cg.io.outputs
  io.outputs <> co.io.outputs

  // This generates the statistics for compression.
  if (params.debugStatistics) {
    // These wires are used to store the final output values of character depths
    val characterDepths = Wire(
      Vec(params.codewordMaxBits + 1, UInt(params.characterCountBits.W))
    )

    for (index <- 0 to params.codewordMaxBits) {
      characterDepths(index) := PopCount(cg.io.inputs.depthsOut.map(_ === index.U))
    }

    io.statistics.get.huffmanCharacterDepths := characterDepths
    io.statistics.get.escapeCharacterLength := cg.io.outputs.escapeCharacterLength
    io.statistics.get.huffmanTreeCharactersUsed := characterDepths.reduce(_ + _)(0.U)
  }
}

object huffmanCompressor extends App {
  val settingsGetter = new getHuffmanFromCSV()
  chisel3.Driver
    .execute(
      Array[String](),
      () =>
        new huffmanCompressor(
          settingsGetter.getHuffmanFromCSV("configFiles/huffmanCompressorDecompressor.csv")
        )
    )
}
