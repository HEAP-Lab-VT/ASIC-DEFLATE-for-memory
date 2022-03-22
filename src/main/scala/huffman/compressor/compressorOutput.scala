// See README.md for license details.

package compressorOutput

import chisel3._
import chisel3.util._
import compressorInput._
import inputsAndOutputs._
import huffmanParameters._

/**
  * Compute compressorOutput using subtraction method.
  * Subtracts the smaller from the larger until register y is zero.
  * value in register x is then the compressorOutput
  */
class compressorOutput(params: huffmanParameters) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    //  data in
    val dataIn =
      Vec(params.compressionParallelism, new compressorInputData(params, false))
    // data in

    // codewordGenerator outputs
    val inputs = Flipped(new codewordGeneratorOutputs(params))
    // codewordGeneratorOutputs
    val outputs =
      Vec(params.compressionParallelism, new compressorOutputData(params))
    val finished = Output(Bool())
  })

  val input =
    Seq.fill(params.compressionParallelism)(
      Module(new compressorInput(params, false))
    )

  val numberOfStates = 4
  // The writingCompressionLimit doesn't get used unless variable compression is enabled.
  val waiting :: writingTree :: writingData :: writingCompressionLimit :: Nil =
    Enum(numberOfStates)
  val state = RegInit(UInt(log2Ceil(numberOfStates + 1).W), waiting)

  // This is needed in case the state is not one of the defined states. Sadly, Chisel does not yet
  // support a "default" for switch statements, so  this needs to be used to be sure that the outputs
  // are defined for all scenarios.
  for (index <- 0 until params.compressionParallelism) {
    io.outputs(index).dataOut := 0.U
    io.outputs(index).valid := false.B
    io.outputs(index).dataLength := 0.U
    input(index).io.dataOut.ready := false.B
    input(index).io.currentByte := 0.U
    input(index).io.input <> io.dataIn(index)
    input(index).io.start <> io.start
  }

  switch(state) {
    is(waiting) {
      when(io.start) {
        state := writingTree
      }
    }

    is(writingTree) {
      // The writing tree phase consists of the output with the index 0 outputting the huffman tree one by one while the other outputs do not output anything.
      // Because it isn't encoding yet, none of the inputs need to be read from.
      for (index <- 0 until params.compressionParallelism) {
        input(index).io.dataOut.ready := false.B
      }

      // This sets empty values for all of the outputs that aren't working during this state.
      for (index <- 1 until params.compressionParallelism) {
        io.outputs(index).dataOut := 0.U
        io.outputs(index).valid := false.B
        io.outputs(index).dataLength := 0.U
      }
      val iterations =
        Seq(RegInit(UInt(log2Ceil(params.huffmanTreeCharacters).W), 0.U))
      // Sets some of the outputs of the primary serial tree output. Dictionary entry length is always the same between
      // different entries.
      io.outputs(0).valid := true.B
      io.outputs(0).dataLength := params.dictionaryEntryMaxBits.U

      when(iterations(0) < io.inputs.nodes) {
        when(
          io.inputs.charactersOut(iterations(0))(params.characterBits) === 1.U
        ) {
          // This is an escape character.
          io.outputs(0).dataOut := Cat(
            io.inputs.charactersOut(iterations(0)),
            Cat(
              io.inputs.escapeCodeword(params.codewordMaxBits - 1, 0),
              io.inputs.escapeCharacterLength
            )
          )
        }.otherwise {
          // This is not an escape character.
          io.outputs(0).dataOut := Cat(
            io.inputs.charactersOut(iterations(0)),
            Cat(
              io.inputs.codewords(io.inputs.charactersOut(iterations(0)))(
                params.codewordMaxBits - 1,
                0
              ),
              io.inputs.lengths(io.inputs.charactersOut(iterations(0)))(
                params.codewordLengthMaxBits - 1,
                0
              )
            )
          )
        }
      }.otherwise {
        // All output is 0 for characters that don't need to be utilized by the decompressor.
        io.outputs(0).dataOut := 0.U
      }

      when(io.outputs(0).ready) {
        when(when.cond){iterations(0) := iterations(0) + 1.U}
        when(iterations(0) === (params.huffmanTreeCharacters - 1).U) {
          state := writingData
        }
      }
    }

    is(writingData) {
      for (index <- 0 until params.compressionParallelism) {
        input(index).io.dataOut.ready := io.outputs(index).ready
        io.outputs(index).valid := input(index).io.dataOut.valid
        io.outputs(index).dataLength :=
          io.inputs.lengths(input(index).io.dataOut.bits(0))
        io.outputs(index).dataOut :=
          io.inputs.codewords(input(index).io.dataOut.bits(0))
          .<<(params.dictionaryEntryMaxBits.U - io.outputs(index).dataLength)
      }
    }
  }

  io.finished := state === waiting
}

object compressorOutput extends App {
  // chisel3.Driver.execute(Array[String](), () => new compressorOutput)
}
