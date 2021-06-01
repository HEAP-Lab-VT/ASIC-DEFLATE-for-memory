package cacheLineBitGetter

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

// This module takes in a number representing the first bit that the decompressor needs and can access across multiple cache lines to access a minimum number of
// bits to be sure a character isn't incorrectly cut off. This should be used in all cachelinewrappers, but I am going to hold off on it to avoid accidentally
// introducing bugs.
class cacheLineBitGetter(
    // This is how many bits are in a given byte of the input.
    bitsPerByte: Int = 8,
    // This is how many bits are in a single cycle of the input.
    bytesPerCacheLine: Int = 64,
    // This is how many bits are in a single cycle of the output.
    bitsPerOutput: Int = 28,
    // This is how many bytes are in the data in memory.
    bytesInData: Int = 4096
) extends Module {
  // This is how many bits are in a single cache line of the input.
  val bitsPerCacheLine = bytesPerCacheLine * bitsPerByte
  // This is how many different cache lines need to be stored at once in the worst case scenario of data being spread across cacheline boundaries.
  // This isn't completely parametrized, but making it fully parametrized is likely a complete waste, as the likelihood of a single piece of data being
  // over 512 bits long (64 bytes), is very low.
  val worstCaseScenarioCacheLinesStored = 2
  if (worstCaseScenarioCacheLinesStored < 2) {
    println(
      "Error, the worstCaseScenarioCacheLinesStored can't be less than 2 or the hardware will not be able to handle\n" +
        "an output split between lines."
    )
    sys.exit(1)
  }
  // This is the number of bits in the data being accessed.
  val bitsInData = bytesInData * bitsPerByte
  // This is the number of bits needed to address any given bit in the data being accessed.
  val bitAddressableDataBits = log2Ceil(bitsInData)
  // THis is the number of bits needed to address a given cache line of the data being accessed.
  val readPointerBits = log2Ceil(bytesInData / bytesPerCacheLine)

  val io =
    IO(new Bundle {
      // This is the interface for requesting reads.
      val readFifoPointer = Decoupled(UInt(readPointerBits.W))
      // This is the interface to receive the read data.
      val inputData = Flipped(Decoupled(UInt(bitsPerCacheLine.W)))
      // This is the interface to receive a request for a given bit.
      val currentlyRequestedBit = Input(UInt(bitAddressableDataBits.W))
      // This is the interface to output the data in the buffer.
      val outputData = Decoupled(UInt(bitsPerOutput.W))
    })

  // This calculates the address of the first cache line that has data.
  val firstCacheLineWithData = Wire(UInt(bitAddressableDataBits.W))
  firstCacheLineWithData := io.currentlyRequestedBit / bitsPerCacheLine.U

  // This buffer holds as many cache lines as are required to get full-length output data. Probably just 2 in most cases.
  val buffer = RegInit(VecInit(Seq.fill(worstCaseScenarioCacheLinesStored)(0.U(bitsPerCacheLine.W))))
  // This stores the current address of the first line in the buffer.
  val bufferStartAddress = RegInit(UInt(readPointerBits.W), 0.U)
  // This stores whether or not each line in the buffer is valid for the address it is supposed to have.
  val buffersValid = RegInit(VecInit(Seq.fill(worstCaseScenarioCacheLinesStored)(false.B)))
  // This stores whether a read request has been sent that hasn't been read yet.
  val waitingToReceiveReadData = RegInit(Bool(), false.B)
  // This stores the address that was requested from the last read request
  val readAddress = RegInit(UInt(readPointerBits.W), 0.U)

  // These are the default signal values.
  io.inputData.ready := false.B
  io.outputData.valid := false.B
  io.readFifoPointer.valid := false.B
  io.readFifoPointer.bits := 0.U
  io.outputData.bits := 0.U

  // This is controls the inputs and outputs.
  when(bufferStartAddress =/= firstCacheLineWithData) {
    // The first buffer start address is no longer the correct one, so check if any of the other buffer lines have the data that's desired.
    bufferStartAddress := firstCacheLineWithData
    when(firstCacheLineWithData - 1.U === bufferStartAddress && buffersValid(1)) {
      // The second cache line stored in the buffer has the data, so instead of doing two cache accesses, only a single cache access is needed
      // to fully refill the buffer.
      buffersValid(0) := true.B
      buffersValid(1) := false.B
      buffer(0) := buffer(1)
    }.otherwise {
      buffersValid(0) := false.B
      buffersValid(1) := false.B
    }
  }.otherwise {
    // The first buffer start address is the correct one, so next the number of bits should be calculated to be sure that the data can be safely read.
    when((io.currentlyRequestedBit % bitsPerCacheLine.U) + bitsPerOutput.U <= bitsPerCacheLine.U) {
      // If the requested bits never cross over between the two cache lines, the first cache line is the only one that needs to be valid.
      io.outputData.bits := buffer(0) >> (bitsPerCacheLine.U - bitsPerOutput.U - (io.currentlyRequestedBit % bitsPerCacheLine.U))
      when(buffersValid(0)) {
        // The valid bit is set here, but unlike a normal ready-valid interface, we don't need to change the state in any way. The state only needs
        // to change when the bit being accessed is no longer stored in the buffer.
        io.outputData.valid := true.B
      }
    }.otherwise {
      // The requested bits cross over between the two cache lines, so both cache lines that are buffered need to be valid.
      // This concatenates the two buffers and accesses the requested bits from the concatenation.
      io.outputData.bits := Cat(buffer(0), buffer(1)) >> ((2 * bitsPerCacheLine).U - bitsPerOutput.U - (io.currentlyRequestedBit % bitsPerCacheLine.U))
      when(buffersValid(0) && buffersValid(1)) {
        // The valid bit is set here, but unlike a normal ready-valid interface, we don't need to change the state in any way. The state only needs
        // to change when the bit being accessed is no longer stored in the buffer.
        io.outputData.valid := true.B
      }
    }
  }

  // This is a state machine to control the accessing of cachelines.
  when(buffersValid.reduce(_ & _)(false.B)) {
    // If any of the buffers are not valid, start by reading the address of the lowest invalid buffer line.
    val lowestInvalidBufferIndex = PriorityEncoder(~buffersValid.asUInt())
    when(waitingToReceiveReadData) {
      // This waits until the data is ready, then reads it. Must be sure that the data that is received is the correct address cache line.
      when(readAddress === (firstCacheLineWithData + lowestInvalidBufferIndex)) {
        // The data being read is the correct address.
        io.inputData.ready := true.B
        when(io.inputData.valid) {
          waitingToReceiveReadData := false.B
          buffersValid(lowestInvalidBufferIndex) := true.B
          buffer(lowestInvalidBufferIndex) := io.inputData.bits
        }
      }.otherwise {
        // The data being read is not the correct address. Clear the input and request a new address.
        io.inputData.ready := true.B
        when(io.inputData.valid) {
          waitingToReceiveReadData := false.B
        }
      }
    }.otherwise {
      // If the buffer is not yet waiting to receive data, request some.
      val nextReadAddress = firstCacheLineWithData + lowestInvalidBufferIndex
      io.readFifoPointer.valid := true.B
      io.readFifoPointer.bits := nextReadAddress
      when(io.readFifoPointer.ready) {
        waitingToReceiveReadData := true.B
        readAddress := nextReadAddress
      }
    }
  }
}

object cacheLineBitGetter extends App {
  chisel3.Driver
    .execute(Array[String](), () => new cacheLineBitGetter)
}
