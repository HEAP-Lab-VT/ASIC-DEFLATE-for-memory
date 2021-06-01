package cacheLineStitcher

/*
import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._
// This module takes in cache lines and buffers them such that data that exists partly in two or more cache lines can be read all at once.
class cacheLineStitcher(
    // This is how many bits are in a single cycle of the input.
    bitsPerInput: Int = 512,
    // This is how many bits are in a single cycle of the output.
    bitsPerOutput: Int = 28
) extends Module {
  // This is how many bits are in the buffer.
  val bufferBits = bitsPerInput + bitsPerOutput - 1
  // This is how many bits are required to keep a count of the number of bits in the buffer.
  val bufferCountBits = log2Ceil(bufferBits + 1)
  val io =
    IO(new Bundle {
      // This is the interface to receive the input data.
      val inputData = Flipped(Decoupled(UInt(bitsPerInput.W)))
      // This is the interface to output the data in the buffer.
      val outputData = Decoupled(UInt(bitsPerOutput.W))
    })

  io.outputData.bits := buffer(bufferBits - 1, bufferBits - bitsPerOutput)

  // These are the default signal values.
  io.inputData.ready := false.B
  io.outputData.valid := false.B

  val buffer = RegInit(UInt(bufferBits.W), 0.U)
  val bufferBitCount = RegInit(UInt(bufferCountBits.W), 0.U)

  when(bufferBitCount >= bitsPerOutput.U) {
    // This outputs the current buffer and gets ready to start taking in data again by clearing the necessary registers.
    io.inputData.ready := false.B
    io.outputData.valid := true.B
    when(io.outputData.ready) {
      // This clears the buffer and moves the leftover bits to the beginning of the buffer.
      bufferBitCount := bufferBitCount - bitsPerOutput.U
      buffer := buffer << bitsPerOutput
    }
  }.otherwise {
    io.outputData.valid := false.B
    io.inputData.ready := true.B
    when(io.inputData.valid) {
      bufferBitCount := bufferBitCount + bitsPerInput.U
      buffer := buffer | ((io.inputData.bits) << (bitsPerInput.U - 1.U - bufferBitCount))
    }
  }

}

object cacheLineStitcher extends App {
  chisel3.Driver
    .execute(Array[String](), () => new cacheLineStitcher)
}
*/