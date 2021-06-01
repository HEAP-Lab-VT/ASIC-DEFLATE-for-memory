// See README.md for license details.

package decompressorOutput

import chisel3._
import chisel3.util._
import huffmanParameters._
import inputsAndOutputs._

class decompressorOutput(
    characterBits: Int = 8,
    sortMuxBits: Int = 12, // 2 to this power is the number of input characters.
    outputRegister: Boolean = true // This tells whether to use an output register or to set all the outputs to 0 except for the first byte
) extends Module {

  val dataOutSize = if (outputRegister) {
    1 << sortMuxBits
  } else {
    1
  }

  val io = IO(new Bundle {
    val start = Input(Bool())
    val dataIn = Flipped(Decoupled(UInt(characterBits.W)))
    val output =
      new decompressorOutputData(outputRegister, 1 << sortMuxBits, characterBits)
    val finished = Output(Bool())
  })

  val waiting :: outputting :: Nil = Enum(2)

  val state = RegInit(UInt(1.W), waiting)

  val iteration = Reg(UInt((sortMuxBits + 1).W)) // This is the current iteration in a state.

  if (outputRegister) {
    val dataOut = Reg(Vec(dataOutSize, UInt(characterBits.W)))

    when(state === waiting) {
      io.dataIn.ready := false.B
      when(io.start === true.B) {
        state := outputting
        iteration := 0.U
      }
    }.otherwise {
      io.dataIn.ready := true.B
      when(io.dataIn.valid) {
        dataOut(iteration) := io.dataIn.bits
        iteration := iteration + 1.U
        when(iteration >= ((1 << sortMuxBits) - 1).U) {
          state := waiting
        }
      }
    }

    io.output.dataOutArray := dataOut
    io.output.valid := true.B

    // compiler gets mad without this
    io.output.dataOut := 0.U
  } else {

    when(state === waiting) {
      io.dataIn.ready := false.B
      when(io.start === true.B) {
        state := outputting
        iteration := 0.U
      }
    }.otherwise {
      io.dataIn.ready := true.B
      when(io.dataIn.valid && io.output.ready) {
        iteration := iteration + 1.U
        when(iteration >= ((1 << sortMuxBits) - 1).U) {
          state := waiting
        }
      }
    }

    io.output.dataOut := io.dataIn.bits
    io.output.valid := state =/= waiting
    io.dataIn.ready := true.B

    // compiler gets mad without this
    io.output.dataOutArray := Seq.fill(1 << sortMuxBits)(0.U)
  }
    io.finished := state === waiting
}

object decompressorOutput extends App {
  chisel3.Driver.execute(Array[String](), () => new decompressorOutput)
}
