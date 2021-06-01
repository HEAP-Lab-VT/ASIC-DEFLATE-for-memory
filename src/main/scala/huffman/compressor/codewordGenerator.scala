// See README.md for license details.

package codewordGenerator

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

/**
  * Compute codewordGenerator using subtraction method.
  * Subtracts the smaller from the larger until register y is zero.
  * value in register x is then the codewordGenerator
  */
class codewordGenerator(params: huffmanParameters) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputs = Flipped(
      new treeNormalizerOutputs(
        params.characterRepresentationBits,
        params.huffmanTreeCharacters,
        params.characterRepresentationBits,
        params.treeDepthOutputBits
      )
    )
    val outputs = new codewordGeneratorOutputs(params)
    val finished = Output(Bool())
  })

  val waiting :: orderingCodewords :: calculatingCodewords :: fillingInCodewords :: Nil =
    Enum(4)

  val state = RegInit(UInt(2.W), waiting)

  val depthCounts = RegInit(VecInit(Seq.fill(16)(0.U(4.W))))
  val charactersIn = RegInit(VecInit(Seq.fill(params.huffmanTreeCharacters)(0.U(params.characterRepresentationBits.W)))) // This stores
  // the characters as they come in. This is important because it is in the
  // order that their codewords will be generated, meaning that the
  // compressorOutput will need this to be able to communicate correctly with
  // the decompressor.
  val depths = RegInit(VecInit(Seq.fill(params.huffmanTreeCharacters)(0.U(params.codewordLengthMaxBits.W))))
  val codewords = RegInit(VecInit(Seq.fill(params.characterPossibilities)(0.U(params.codewordMaxBits.W)))) // This stores the codewords out of order as they are being generated
  val codewordsOut = Reg(Vec(params.characterPossibilities, UInt(params.codewordPlusCharacterBits.W))) // This stores codewords for being outputted
  val lengths = RegInit(VecInit(Seq.fill(params.characterPossibilities)(0.U(params.escapeCharacterLengthMaxBits.W))))
  val lengthsOut = Reg(Vec(params.characterPossibilities, UInt(params.escapeCharacterLengthMaxBits.W)))
  val escapeCharacters = RegInit(VecInit(Seq.fill(params.characterPossibilities)(false.B)))
  val characterIndex = RegInit(UInt(params.characterBits.W), 0.U)
  val nodes = RegInit(UInt(params.characterCountBits.W), 0.U)
  val characterDepth = RegInit(UInt(params.codewordLengthMaxBits.W), 0.U)
  val codeword = RegInit(UInt(params.codewordMaxBits.W), 0.U)
  val escapeCodeword = RegInit(UInt(params.codewordMaxBits.W), 0.U)
  val escapeCharacterLength = RegInit(UInt(params.codewordLengthMaxBits.W), 0.U)
  val treeMaxDepth = Reg(UInt(params.codewordLengthMaxBits.W)) // Just realized that, because this is a
  // finite state machine, really the only thing that needs to pay attention to
  // reset is the state, but I'm not going to delete all the other reginits
  // unless I change my mind.

  when(state === waiting) {
    when(io.start === true.B) {
      state := orderingCodewords
      charactersIn := io.inputs.charactersOut
      depths := io.inputs.depthsOut
      depthCounts := VecInit(Seq.fill(16)(0.U(4.W)))
      characterIndex := 0.U
      nodes := io.inputs.validNodesOut
      characterDepth := 1.U
      codeword := 0.U
      treeMaxDepth := 0.U
      escapeCharacterLength := 0.U
    }
  }.elsewhen(state === orderingCodewords) {
    // Character index is being used as the iterator here. This makes sure that
    // the escape codeword is the final codeword being generated.
    when(depths(characterIndex) === depths(characterIndex + 1.U)) {
      when(charactersIn(characterIndex) === (1 << params.characterBits).U) {
        // This is the escape character, but it is not the final character in
        // the sequence, so swap its place with the place of the next character.
        charactersIn(characterIndex) := charactersIn(characterIndex + 1.U)
        charactersIn(characterIndex + 1.U) := charactersIn(characterIndex)
      }
    }

    characterIndex := characterIndex + 1.U
    when(characterIndex >= nodes - 1.U) {
      state := calculatingCodewords
      characterIndex := 0.U
    }
  }.elsewhen(state === calculatingCodewords) {
    when(treeMaxDepth < depths(characterIndex)) {
      treeMaxDepth := depths(characterIndex)
    }

    when(characterDepth < depths(characterIndex)) {
      // This makes sure that codewords of the correct length are being generated.
      characterDepth := depths(characterIndex)
      codeword := codeword << (depths(characterIndex) - characterDepth)
    }.otherwise {
      characterIndex := characterIndex + 1.U // This assignment only updates on the next cycle
      // TODO: I think item 0 of the depthCounts array isn't used, can probably remove it with some array math instead of just leaving it and letting synthesis optimize it away.
      depthCounts(depths(characterIndex)) := depthCounts(
        depths(characterIndex)
      ) + 1.U
      when(codeword =/= ((1.U << characterDepth) - 1.U)) {
        // This makes sure that the value doesn't overflow for the
        // codeword
        codeword := codeword + 1.U
      }
      when(charactersIn(characterIndex) === (1.U<<(params.characterBits))) {
        // This is the escape character.
        escapeCodeword := codeword
        escapeCharacterLength := characterDepth
      }.otherwise {
        codewords(charactersIn(characterIndex)) := codeword
        lengths(charactersIn(characterIndex)) := characterDepth
      }
    }

    when(characterIndex >= nodes - 1.U) {
      state := fillingInCodewords
      characterIndex := 0.U
    }

  }.otherwise {
    // Each codeword and length pair is generated in the order that its
    // character comes in. For instance, the codeword of the character 243 will
    // be in position 243 in the codeword vector of registers, and the same for
    // the length of the codeword.
    characterIndex := characterIndex + 1.U
    when(characterIndex >= (params.characterPossibilities - 1).U) {
      state := waiting
    }
    when(lengths(characterIndex) === 0.U) {
      codewordsOut(characterIndex) := Cat(escapeCodeword, characterIndex)
      // This addition needs to be 5-bit instead of 4-bit, or else it will miss certain values. The way Chisel does addition is stupid.
      lengthsOut(characterIndex) := escapeCharacterLength +& params.characterBits.U(params.escapeCharacterLengthMaxBits.W)
      escapeCharacters(characterIndex) := true.B
    }.otherwise {
      codewordsOut(characterIndex) := codewords(characterIndex)
      lengthsOut(characterIndex) := lengths(characterIndex)
      escapeCharacters(characterIndex) := false.B
    }
  }

  io.outputs.codewords := codewordsOut
  io.outputs.lengths := lengthsOut
  io.outputs.charactersOut := charactersIn
  io.outputs.nodes := nodes
  io.outputs.escapeCharacters := escapeCharacters
  io.outputs.escapeCharacterLength := escapeCharacterLength
  io.outputs.depthCounts := depthCounts
  io.outputs.treeMaxDepth := treeMaxDepth
  io.outputs.escapeCodeword := escapeCodeword
  io.finished := state === waiting
}

object codewordGenerator extends App {
  // chisel3.Driver.execute(Array[String](), () => new codewordGenerator)
}
