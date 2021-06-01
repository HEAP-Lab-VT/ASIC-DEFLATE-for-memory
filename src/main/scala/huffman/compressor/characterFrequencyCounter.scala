// See README.md for license details.

package characterFrequencyCounter

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class characterFrequencyCounter(params: huffmanParameters) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val dataIn =
      Flipped(
        Decoupled(Vec(params.characterFrequencyParallelism, UInt(params.characterBits.W)))
      )
    val currentByte = Output(UInt(params.inputCharacterAddressBits.W))
    val frequencies = Output(
      Vec(params.characterPossibilities, UInt(params.inputCharacterBits.W))
    )
    val finished = Output(Bool())
    val compressionLimit = if (params.variableCompression) Some(Input(UInt(params.inputCharacterBits.W))) else None
  })

  val waiting :: counting :: Nil = Enum(2)

  val state = RegInit(UInt(1.W), waiting)

  // It is necessary that this be one more bit than necessary to address all the characters so the comparison with the total
  // number of characters doesn't overflow.
  val currentByte = Reg(UInt(params.inputCharacterBits.W))

  val frequencyTotals = Reg(
    Vec(params.characterPossibilities, UInt(params.inputCharacterBits.W))
  )

  // Default values
  io.dataIn.ready := false.B

  switch(state) {
    is(waiting) {
      when(io.start) {
        currentByte := 0.U
        state := counting
        frequencyTotals := VecInit(
          Seq.fill(params.characterPossibilities)(
            0.U(params.inputCharacterBits.W)
          )
        )
      }
    }
    is(counting) {
      io.dataIn.ready := true.B
      when(io.dataIn.valid) {
        // This wire is used to prevent invalid data getting into the counting. If only part of the parallel inputs are valid, the remaining inputs are
        // set to 0. This shouldn't hurt anything or significantly reduce the compression ratios.
        val dataInModified = Wire(Vec(params.characterFrequencyParallelism, UInt(params.characterBits.W)))
        for (index <- 0 until params.characterFrequencyParallelism) {
          if (params.variableCompression) {
            when(currentByte + index.U < io.compressionLimit.get) {
              // When the current byte being looked at is less than the compression limit, it is valid, set it to the input.
              dataInModified(index) := io.dataIn.bits(index)
            }.otherwise {
              // When the current byte being looked at is not less than the compression limit, it is invalid, so treat it as a 0 instead.
              dataInModified(index) := 0.U
            }
          } else {
            dataInModified(index) := io.dataIn.bits(index)
          }
        }
        for (index <- 0 until params.characterPossibilities) {
          // It is possible that this could be augmented to work for any amount of parallelism, not just integer factors
          // by using Scala's Zip function to zip up the dataIn bits with a bit provided that shows if that bit is a valid bit
          // or if it has overflowed the total number of characters.
          frequencyTotals(index) := frequencyTotals(index) + dataInModified.count(character => character === index.U)
        }
        currentByte := currentByte + params.characterFrequencyParallelism.U

        if (params.variableCompression) {
          // This stops the counting once all the relevant data has been counted.
          when(currentByte >= io.compressionLimit.get - params.characterFrequencyParallelism.U) {
            state := waiting
          }
        } else {
          when(currentByte >= (params.characters - params.characterFrequencyParallelism).U) {
            state := waiting
          }
        }
      }
    }
  }

  io.currentByte := currentByte
  io.frequencies := frequencyTotals
  io.finished := state === waiting
}

object characterFrequencyCounter extends App {
  // chisel3.Driver.execute(Array[String](), () => new characterFrequencyCounter)
}
