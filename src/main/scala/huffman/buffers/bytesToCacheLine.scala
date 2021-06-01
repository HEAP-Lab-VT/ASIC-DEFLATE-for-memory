package bytesToCacheLine

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class bytesToCacheLine(
    // This is the maximum number of bits in the input data. It is possible for the input data to consist of less than this many bits.
    inputDataBits: Int = 8,
    // This is the number of bytes in the output data.
    outputDataBytes: Int = 64,
    // This is how many bits are in a single byte of the output data.
    bitsPerByte: Int = 8
) extends Module {
  // This is how many bits needed to describe how many bits the input data is.
  val dataLengthBits = log2Ceil(inputDataBits + 1)
  // This is how many bits are in the output data.
  val outputDataBits = outputDataBytes * bitsPerByte
  // This is how many extra bits are in the buffer.
  val extraBufferBits = inputDataBits - 1
  // The number of bits in the buffer is larger than the number of bits in an output because it is possible for the number of input bits and the number
  // of output bits not to match up perfectly.
  val bufferBits = outputDataBits + extraBufferBits
  // This is the number of bits needed to keep track of how many bits are currently in the buffer.
  val bufferCountBits = log2Ceil(1 + bufferBits)
  val io =
    IO(new Bundle {
      // This is the interface to receive the input data.
      val inputData = Flipped(Decoupled(UInt(inputDataBits.W)))
      // This is tells the length of the received input data.
      val inputDataLength = Input(UInt(dataLengthBits.W))
      // This allows the driver to dump the existing buffer and clear it even if the buffer is not full.
      val dumpBuffer = Input(Bool())
      // This tells how many bits are currently being stored in the buffer so drivers know whether it is empty.
      val currentBufferBits = Output(UInt(bufferCountBits.W))

      val outputData = Decoupled(UInt(outputDataBits.W))
    })

  val currentBufferBits = RegInit(UInt(bufferCountBits.W), 0.U)
  io.currentBufferBits <> currentBufferBits
  val buffer = Reg(UInt(bufferBits.W))

  when(io.dumpBuffer) {
    io.outputData.valid := true.B
    io.inputData.ready := false.B
    when(io.outputData.ready) {
      // Clear the buffer and set the current number of bits in the buffer to 0.
      currentBufferBits := 0.U
      buffer := 0.U
    }
  }.otherwise {
    when(currentBufferBits >= outputDataBits.U) {
      // This outputs the current buffer and gets ready to start taking in data again by clearing the necessary registers.
      io.inputData.ready := false.B
      io.outputData.valid := true.B
      when(io.outputData.ready) {
        // This clears the buffer and moves the leftover bits to the beginning of the buffer. If there are no leftover bits, just clear.
        currentBufferBits := currentBufferBits - outputDataBits.U
        when(currentBufferBits === outputDataBits.U) {
          buffer := 0.U
        }.otherwise {
          buffer := buffer << outputDataBits
        }
      }
    }.otherwise {
      io.outputData.valid := false.B
      io.inputData.ready := true.B
      when(io.inputData.valid) {
        // This ignores the bits of the input that are longer than the inputDataLength says and adds the input to the buffer.
        currentBufferBits := currentBufferBits + io.inputDataLength
        buffer := buffer | ((io.inputData.bits & ~(Fill(inputDataBits, true.B) << io.inputDataLength)) << (bufferBits.U - io.inputDataLength - currentBufferBits))
      }
    }
  }

  // The buffer needs to be shifted to the right before being output, because it has a number of extra bits.
  io.outputData.bits := buffer >> extraBufferBits
}

object bytesToCacheLine extends App {
  chisel3.Driver
    .execute(Array[String](), () => new bytesToCacheLine)
}
