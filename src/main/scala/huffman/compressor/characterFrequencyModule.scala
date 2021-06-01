// See README.md for license details.

package characterFrequencyModule

import chisel3._
import chisel3.util._
import characterFrequencyCounter._
import characterFrequencySort._
import compressorInput._
import inputsAndOutputs._
import huffmanParameters._

class characterFrequencyModule(params: huffmanParameters) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val input =
      new compressorInputData(params, true)
    val outputs = new characterFrequencyModuleOutputs(params.huffmanTreeCharacters, params.inputCharacterBits, params.characterBits)
    val finished = Output(Bool())
  })

  val input = Module(new compressorInput(params, true))

  val count = Module(new characterFrequencyCounter(params))

  val sort = Module(
    new characterFrequencySort(params)
  )

  val previousCountFinished = RegNext(count.io.finished)

  val waiting :: counting :: sorting :: Nil = Enum(3)

  val state = RegInit(UInt(2.W), waiting)

  sort.io.start := count.io.finished && ~previousCountFinished
  io.finished := sort.io.finished
  sort.io.dataIn <> count.io.frequencies
  count.io.dataIn <> input.io.dataOut
  if (params.variableCompression) {
    count.io.compressionLimit.get := input.io.compressionLimit.get
  }
  input.io.input <> io.input
  input.io.start <> io.start
  count.io.start <> io.start
  input.io.currentByte <> count.io.currentByte
  io.outputs.sortedFrequency <> sort.io.sortedFrequency
  io.outputs.sortedCharacter <> sort.io.sortedCharacter
}

object characterFrequencyModule extends App {
  // chisel3.Driver.execute(Array[String](), () => new characterFrequencyModule)
}
