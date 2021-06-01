// See README.md for license details.

package characterFrequencySort

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class characterFrequencySort(params: huffmanParameters) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val dataIn = Input(Vec(1 << params.characterBits, UInt(params.inputCharacterBits.W)))
    val sortedFrequency = Output(Vec(params.huffmanTreeCharacters, UInt(params.inputCharacterBits.W)))
    val sortedCharacter =
      Output(Vec(params.huffmanTreeCharacters, UInt((params.characterBits + 1).W)))
    val finished = Output(Bool())
  })

  val waiting :: sorting :: insertingEscapeCodeword :: Nil = Enum(3)

  val state = RegInit(UInt(2.W), waiting)

  val data = if (params.compressorParallelInput) {
    Reg(Vec(1 << params.characterBits, UInt(params.inputCharacterBits.W)))
  } else {
    io.dataIn
  }

  val iteration = Reg(UInt((params.characterBits + 1).W)) // This is the current iteration in a state.

  // These are the registers that will be used to sort and output the characters and frequencies.
  val sortedFrequency = Reg(Vec(params.huffmanTreeCharacters, UInt(params.inputCharacterBits.W)))
  val sortedCharacter = Reg(Vec(params.huffmanTreeCharacters, UInt((params.characterBits + 1).W)))
  // These are used to assist the sorting process.
  val sortedFrequencyTemp = Reg(Vec(params.huffmanTreeCharacters, UInt(params.inputCharacterBits.W)))
  val sortedCharacterTemp = Reg(
    Vec(params.huffmanTreeCharacters, UInt((params.characterBits + 1).W))
  )

  val escapeFrequency = Reg(UInt(params.inputCharacterBits.W))

  val frequencyInput = Wire(UInt(params.inputCharacterBits.W))
  val characterInput = Wire(UInt((params.characterBits + 1).W))

  frequencyInput := 0.U
  characterInput := 0.U

  switch(state) {
    is(waiting) {
      when(io.start === true.B) {
        state := sorting
        if (params.compressorParallelInput) {
          data := io.dataIn
        }
        sortedFrequency := VecInit(Seq.fill(params.huffmanTreeCharacters)(0.U(params.inputCharacterBits.W)))
        sortedFrequencyTemp := VecInit(
          Seq.fill(params.huffmanTreeCharacters)(0.U(params.inputCharacterBits.W))
        )
        iteration := 0.U
        escapeFrequency := 0.U
      }
    }
    is(sorting) {
      when(iteration < (1.U << params.characterBits.U)) {
        frequencyInput := data(iteration)
        characterInput := iteration
      }.otherwise {
        frequencyInput := 0.U
        characterInput := 0.U
      }

      // This starts the chain of shifts.
      when(frequencyInput > sortedFrequency(0)) {
        sortedFrequencyTemp(0) := sortedFrequency(0)
        sortedCharacterTemp(0) := sortedCharacter(0)
        sortedFrequency(0) := frequencyInput
        sortedCharacter(0) := characterInput
      }.otherwise {
        sortedFrequencyTemp(0) := frequencyInput
        sortedCharacterTemp(0) := characterInput
      }

      //   // This does most of the shifts, but does not include the final shift because that shift
      //   // also adds to the escape character count.
      for (index <- 1 until params.huffmanTreeCharacters - 1) {
        when(sortedFrequencyTemp((index - 1).U) > sortedFrequency(index.U)) {
          sortedFrequencyTemp(index.U) := sortedFrequency(index.U)
          sortedCharacterTemp(index.U) := sortedCharacter(index.U)
          sortedFrequency(index.U) := sortedFrequencyTemp((index - 1).U)
          sortedCharacter(index.U) := sortedCharacterTemp((index - 1).U)
        }.otherwise {
          sortedFrequencyTemp(index.U) := sortedFrequencyTemp((index - 1).U)
          sortedCharacterTemp(index.U) := sortedCharacterTemp((index - 1).U)
        }
      }

      // This does the final shift, adding whatever is shifted out to the escape character.
      when(
        sortedFrequencyTemp((params.huffmanTreeCharacters - 2).U) > sortedFrequency(
          (params.huffmanTreeCharacters - 1).U
        )
      ) {
        escapeFrequency := escapeFrequency + sortedFrequency(
          (params.huffmanTreeCharacters - 1).U
        )
        sortedFrequency((params.huffmanTreeCharacters - 1).U) := sortedFrequencyTemp(
          (params.huffmanTreeCharacters - 2).U
        )
        sortedCharacter((params.huffmanTreeCharacters - 1).U) := sortedCharacterTemp(
          (params.huffmanTreeCharacters - 2).U
        )
      }.otherwise {
        escapeFrequency := escapeFrequency + sortedFrequencyTemp(
          (params.huffmanTreeCharacters - 2).U
        )
      }

      iteration := iteration + 1.U
      when(iteration === ((1 << params.characterBits) - 1 + params.huffmanTreeCharacters).U) {
        // Once all characters have been shifted in and have had time to propagate, go to
        // the next state.
        when(escapeFrequency > 0.U) {
          // If there needs to be an escape character, add the frequency of the final
          // character in the chain, because it will be pushed out with the insertion of the escape
          // character.
          escapeFrequency := escapeFrequency + sortedFrequency(
            ((1 << params.characterBits) - 1).U
          )
          state := insertingEscapeCodeword
          iteration := 0.U
        }.otherwise {
          // No escape character, so the sort is finished.
          state := waiting
        }
      }
    }
    is(insertingEscapeCodeword) {
      // This inserts the escape character the first iteration, then
      // inserts 0 the rest of the time.
      when(iteration === 0.U) {
        frequencyInput := escapeFrequency
        // This inserts the escape character.
        characterInput := (1 << params.characterBits).U
      }.otherwise {
        frequencyInput := 0.U
        characterInput := 0.U
      }

      // This starts the chain of shifts.
      when(frequencyInput > sortedFrequency(0)) {
        sortedFrequencyTemp(0) := sortedFrequency(0)
        sortedCharacterTemp(0) := sortedCharacter(0)
        sortedFrequency(0) := frequencyInput
        sortedCharacter(0) := characterInput
      }.otherwise {
        sortedFrequencyTemp(0) := frequencyInput
        sortedCharacterTemp(0) := characterInput
      }

      // This does most of the shifts, but does not include the final shift because that shift
      // also adds to the escape character count.
      for (index <- 1 until params.huffmanTreeCharacters) {
        when(sortedFrequencyTemp((index - 1).U) > sortedFrequency(index.U)) {
          sortedFrequencyTemp(index.U) := sortedFrequency(index.U)
          sortedCharacterTemp(index.U) := sortedCharacter(index.U)
          sortedFrequency(index.U) := sortedFrequencyTemp((index - 1).U)
          sortedCharacter(index.U) := sortedCharacterTemp((index - 1).U)
        }.otherwise {
          sortedFrequencyTemp(index.U) := sortedFrequencyTemp((index - 1).U)
          sortedCharacterTemp(index.U) := sortedCharacterTemp((index - 1).U)
        }
      }

      iteration := iteration + 1.U
      when(iteration === (params.huffmanTreeCharacters - 1).U) {
        state := waiting
      }
    }
  }

  io.finished := state === waiting
  io.sortedFrequency := sortedFrequency
  io.sortedCharacter := sortedCharacter

}

object characterFrequencySort extends App {
  // chisel3.Driver.execute(Array[String](), () => new characterFrequencySort)
}
