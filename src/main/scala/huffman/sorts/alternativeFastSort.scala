package alternativeFastSort

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

// This class is the combination logic to take in a character and the current state of the keys and tags and insert the character
// wherever it needs to go.
class insertPair(
    // This is the number of pairs of keys and tags that will be sorted.
    inputPairs: Int = 256,
    // This is the number of bits in a key.
    keyBits: Int = 12,
    // This is the number of bits in a tag.
    tagBits: Int = 9
) extends Module {
  val io = IO(new Bundle {
    // The activate is used to turn off the sorting so that bad data isn't added in once
    // the sort is complete.
    val activate = Input(Bool())
    val insertKey = Input(UInt(keyBits.W))
    val insertTag = Input(UInt(tagBits.W))
    val inKeys = Input(Vec(inputPairs, UInt(keyBits.W)))
    val inTags = Input(Vec(inputPairs, UInt(tagBits.W)))
    val outKeys = Output(Vec(inputPairs, UInt(keyBits.W)))
    val outTags = Output(Vec(inputPairs, UInt(tagBits.W)))
  })

  // This creates an array of wires where a 1 means that wire will be shifted down and 0 means it stays in place.
  val shiftDown = Wire(Vec(inputPairs, Bool()))
  shiftDown := io.inKeys.map(io.insertKey >= _)

  io.outKeys := io.inKeys
  io.outTags := io.inTags

  when(io.activate) {
    for (index <- 0 until inputPairs) {
      when(shiftDown(index)) {
        if (index > 0) {
          io.outKeys(index) := io.inKeys(index - 1)
          io.outTags(index) := io.inTags(index - 1)
        }
      }
    }

    // This makes sure that the input should be inserted before doing so.
    when(PopCount(shiftDown) > 0.U) {
      io.outKeys(PriorityEncoder(shiftDown.asUInt)) := io.insertKey
      io.outTags(PriorityEncoder(shiftDown.asUInt)) := io.insertTag
    }
  }
}

class alternativeFastSort(
    // This is the number of pairs of keys and tags that will be sorted.
    inputPairs: Int = 256,
    // This is the number of bits in a key.
    keyBits: Int = 12,
    // This is the number of bits in a tag.
    tagBits: Int = 9,
    // This is the number of iterations of the sort to complete from an input to an output.
    simultaneousIterations: Int = 1
) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inKeys = Input(Vec(inputPairs, UInt(keyBits.W)))
    val inTags = Input(Vec(inputPairs, UInt(tagBits.W)))
    val outKeys = Output(Vec(inputPairs, UInt(keyBits.W)))
    val outTags = Output(Vec(inputPairs, UInt(tagBits.W)))
    val finished = Output(Bool())
  })
  val previousStart = Reg(Bool())
  previousStart := io.start

  val numberOfStates = 2
  val waiting :: sorting :: Nil = Enum(numberOfStates)

  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)
  val previousState = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)
  previousState := state

  val iterations = Reg(UInt(log2Ceil(inputPairs + 1).W))

  val keys = Reg(Vec(inputPairs, UInt(keyBits.W)))
  val tags = Reg(Vec(inputPairs, UInt(tagBits.W)))

  val combinationalLogic = Seq.fill(simultaneousIterations)(Module(new insertPair(inputPairs, keyBits, tagBits)))

  for (index <- 0 until simultaneousIterations) {
    if (index == 0) {
      combinationalLogic(index).io.inKeys <> keys
      combinationalLogic(index).io.inTags <> tags
    } else {
      combinationalLogic(index).io.inKeys <> combinationalLogic(index - 1).io.outKeys
      combinationalLogic(index).io.inTags <> combinationalLogic(index - 1).io.outTags
    }
    combinationalLogic(index).io.insertKey := keys(iterations + index.U)
    combinationalLogic(index).io.insertTag := tags(iterations + index.U)
    when(iterations + index.U < inputPairs.U) {
      combinationalLogic(index).io.activate := true.B
    }.otherwise {
      combinationalLogic(index).io.activate := false.B
    }
  }

  switch(state) {
    is(waiting) {
      when(io.start && previousStart === false.B) {
        state := sorting
        iterations := 0.U
        for (index <- 0 until inputPairs) {
          keys(index) := 0.U
        }
      }
    }

    is(sorting) {
      iterations := iterations + simultaneousIterations.U
      when(iterations >= simultaneousIterations.U) {
        state := waiting
      }
      keys := combinationalLogic(simultaneousIterations - 1).io.outKeys
      tags := combinationalLogic(simultaneousIterations - 1).io.outTags
    }
  }

  io.outKeys <> keys
  io.outTags <> tags

  io.finished := state === waiting && previousState =/= waiting
}

object alternativeFastSort extends App {
  chisel3.Driver.execute(Array[String](), () => new alternativeFastSort)
}
