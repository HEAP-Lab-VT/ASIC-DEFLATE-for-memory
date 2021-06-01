package fastSort

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class fastSort(
    // This is the number of pairs of keys and tags that will be sorted.
    inputPairs: Int = 256,
    // This is the number of bits in a key.
    keyBits: Int = 12,
    // This is the number of bits in a tag.
    tagBits: Int = 9,
    // This is the number of iterations of the sort to complete from an input to an output.
    simultaneousIterations: Int = 256
) extends Module {

  val io = IO(new Bundle {
    val inKeys = Input(Vec(inputPairs, UInt(keyBits.W)))
    val inTags = Input(Vec(inputPairs, UInt(tagBits.W)))
    val outKeys = Output(Vec(inputPairs, UInt(keyBits.W)))
    val outTags = Output(Vec(inputPairs, UInt(tagBits.W)))
  })

  val intermediateKeys = Wire(
    Vec(simultaneousIterations + 1, Vec(2, Vec(inputPairs, UInt(keyBits.W))))
  )
  val intermediateTags = Wire(
    Vec(simultaneousIterations + 1, Vec(2, Vec(inputPairs, UInt(tagBits.W))))
  )

  for (iteration <- 0 to simultaneousIterations) {
    for (index <- 0 until inputPairs) {
      if (iteration == 0 && index % 2 == 0) {
        if ((index + 1) != inputPairs) {
          when(io.inKeys(index) > io.inKeys(index + 1)) {
            intermediateKeys(iteration)(0)(index) := io.inKeys(index)
            intermediateTags(iteration)(0)(index) := io.inTags(index)
            intermediateKeys(iteration)(0)(index + 1) := io.inKeys(index + 1)
            intermediateTags(iteration)(0)(index + 1) := io.inTags(index + 1)
          }.otherwise {
            intermediateKeys(iteration)(0)(index) := io.inKeys(index + 1)
            intermediateTags(iteration)(0)(index) := io.inTags(index + 1)
            intermediateKeys(iteration)(0)(index + 1) := io.inKeys(index)
            intermediateTags(iteration)(0)(index + 1) := io.inTags(index)
          }
        }
      } else {
        // This makes sure The first and last numbers always get propagated through correctly.
        if (index + 1 == inputPairs && inputPairs % 2 == 1) {
          if (iteration == 0) {
            intermediateKeys(iteration)(0)(index) := io.inKeys(index)
            intermediateTags(iteration)(0)(index) := io.inTags(index)
          } else {
            intermediateKeys(iteration)(0)(index) := intermediateKeys(iteration - 1)(1)(index)
            intermediateTags(iteration)(0)(index) := intermediateTags(iteration - 1)(1)(index)
          }
        } else if (index + 1 == inputPairs && inputPairs % 2 == 0) {
          intermediateKeys(iteration)(1)(index) := intermediateKeys(iteration)(0)(index)
          intermediateTags(iteration)(1)(index) := intermediateTags(iteration)(0)(index)
        }
        // This makes sure the 0th element is propagated through on the second phase.
        intermediateKeys(iteration)(1)(0) := intermediateKeys(iteration)(0)(0)
        intermediateTags(iteration)(1)(0) := intermediateTags(iteration)(0)(0)
        if (index % 2 == 0 && (index + 1) != inputPairs) {
          when(intermediateKeys(iteration - 1)(1)(index) > intermediateKeys(iteration - 1)(1)(index + 1)) {
            intermediateKeys(iteration)(0)(index) := intermediateKeys(iteration - 1)(1)(index)
            intermediateTags(iteration)(0)(index) := intermediateTags(iteration - 1)(1)(index)
            intermediateKeys(iteration)(0)(index + 1) := intermediateKeys(iteration - 1)(1)(index + 1)
            intermediateTags(iteration)(0)(index + 1) := intermediateTags(iteration - 1)(1)(index + 1)
          }.otherwise {
            intermediateKeys(iteration)(0)(index) := intermediateKeys(iteration - 1)(1)(index + 1)
            intermediateTags(iteration)(0)(index) := intermediateTags(iteration - 1)(1)(index + 1)
            intermediateKeys(iteration)(0)(index + 1) := intermediateKeys(iteration - 1)(1)(index)
            intermediateTags(iteration)(0)(index + 1) := intermediateTags(iteration - 1)(1)(index)
          }
        }
        if (index % 2 == 1 && (index + 1) != inputPairs) {
          when(intermediateKeys(iteration)(0)(index) > intermediateKeys(iteration)(0)(index + 1)) {
            intermediateKeys(iteration)(1)(index) := intermediateKeys(iteration)(0)(index)
            intermediateTags(iteration)(1)(index) := intermediateTags(iteration)(0)(index)
            intermediateKeys(iteration)(1)(index + 1) := intermediateKeys(iteration)(0)(index + 1)
            intermediateTags(iteration)(1)(index + 1) := intermediateTags(iteration)(0)(index + 1)
          }.otherwise {
            intermediateKeys(iteration)(1)(index) := intermediateKeys(iteration)(0)(index + 1)
            intermediateTags(iteration)(1)(index) := intermediateTags(iteration)(0)(index + 1)
            intermediateKeys(iteration)(1)(index + 1) := intermediateKeys(iteration)(0)(index)
            intermediateTags(iteration)(1)(index + 1) := intermediateTags(iteration)(0)(index)
          }
        }
      }
    }
  }
  io.outKeys <> intermediateKeys(simultaneousIterations)(1)
  io.outTags <> intermediateTags(simultaneousIterations)(1)
}

object fastSort extends App {
  chisel3.Driver.execute(Array[String](), () => new fastSort)
}
