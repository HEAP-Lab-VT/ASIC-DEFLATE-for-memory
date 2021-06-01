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
  // This checks if a thread should be done accepting data when variable compression is enabled.
  def checkIfThreadFinished(iterations: UInt, index: Int, compressionLimit: UInt): Bool = {
    iterations * params.compressionParallelism.U + index.U >= compressionLimit
  }
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

  val iterations = Reg(
    Vec(params.compressionParallelism, UInt(params.inputCharacterBits.W))
  )

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
      for (index <- 0 until params.compressionParallelism) {
        iterations(index) := 0.U
      }
      when(io.start) {
        if (params.variableCompression) {
          state := writingCompressionLimit
        } else {
          state := writingTree
        }
      }
    }

    is(writingCompressionLimit) {
      if (params.variableCompression) {
        for (index <- 0 until params.compressionParallelism) {
          iterations(index) := 0.U
        }
        io.outputs(0).valid := true.B
        io.outputs(0).dataLength := params.inputCharacterBits.U
        io.outputs(0).dataOut := io
          .dataIn(0)
          .compressionLimit
          .get << (params.dictionaryEntryMaxBits.U - io.outputs(0).dataLength)
        when(io.outputs(0).ready) {
          state := writingTree
        }
      }
    }

    is(writingTree) {
      // The writing tree phase consists of the output with the index 0 outputting the huffman tree one by one while the other outputs do not output anything.
      // Because it isn't encoding yet, none of the inputs need to be read from.
      for (index <- 0 until params.compressionParallelism) {
        input(index).io.dataOut.ready := false.B
        input(index).io.currentByte := 0.U
      }

      // This sets empty values for all of the outputs that aren't working during this state.
      for (index <- 1 until params.compressionParallelism) {
        io.outputs(index).dataOut := 0.U
        io.outputs(index).valid := false.B
        io.outputs(index).dataLength := 0.U
      }
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
        iterations(0) := iterations(0) + 1.U
        when(iterations(0) + 1.U >= params.huffmanTreeCharacters.U) {
          state := writingData
          for (index <- 0 until params.compressionParallelism) {
            iterations(index) := 0.U
          }
        }
      }
    }

    is(writingData) {
      // This generates the hardware once for each of the parallel compressors. If all their data is
      // valid, they output the necessary data and go to the next iteration.
      for (index <- 0 until params.compressionParallelism) {
        // Setting the default values
        io.outputs(index).valid := false.B
        io.outputs(index).dataLength := 0.U
        io.outputs(index).dataOut := 0.U
        input(index).io.currentByte := DontCare
        input(index).io.dataOut.ready := false.B

        val continueIterating = Wire(Bool())
        if (params.variableCompression) {
          continueIterating := !checkIfThreadFinished(iterations(index), index, io.dataIn(index).compressionLimit.get)
        } else {
          continueIterating := iterations(index) < params.parallelCharacters.U
        }
        // This performs the parallel compression for threads that still aren't finished
        when(continueIterating) {
          // This calculates which byte is being requested.
          input(index).io.currentByte := iterations(index) * params.compressionParallelism.U + index.U
          // THe input is only ready for data if the output is ready to receive data.
          input(index).io.dataOut.ready := io.outputs(index).ready
          when(input(index).io.dataOut.valid) {
            io.outputs(index).valid := true.B
            when(io.inputs.escapeCharacters(input(index).io.dataOut.bits(0))) {
              // The character is an escape character, so calculate its length.
              io.outputs(index).dataLength := io.inputs.lengths(input(index).io.dataOut.bits(0))
            }.otherwise {
              // The character is not an escape character, so use its defined length.
              io.outputs(index).dataLength := io.inputs.lengths(input(index).io.dataOut.bits(0))
            }
            io.outputs(index).dataOut := io.inputs
              .codewords(input(index).io.dataOut.bits(0)) << (params.dictionaryEntryMaxBits.U - io.outputs(index).dataLength)
            when(io.outputs(index).ready) {
              iterations(index) := iterations(index) + 1.U
            }
          }
        }
      }

      if (params.variableCompression) {
        // Once all the threads of compression have completed all the valid characters, transition into the waiting state.
        when(
          iterations.zipWithIndex.map({ case (value, index) => checkIfThreadFinished(value, index, io.dataIn(index).compressionLimit.get) }).reduce(_ && _)
        ) {
          state := waiting
        }
      } else {
        // Once all the threads of compression are completed, transition to the waiting state.
        // Map performs the anonymous function on each element of iterations (each iteration of
        // the function, the element of the iterations vector replaces the "_" in the input to map),
        // and reduce does an AND reduce on the output Bools from the map function, with each run of
        // the reduction replacing the two "_"s with the corresponding two inputs.
        when(iterations.map(_ >= params.parallelCharacters.U).reduce(_ && _)) {
          state := waiting
        }
      }

    }
  }

  io.finished := state === waiting
}

object compressorOutput extends App {
  // chisel3.Driver.execute(Array[String](), () => new compressorOutput)
}
