package huffmanDecompressorCacheLineWrapper

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._
import bytesToCacheLine._
import cacheLineBitGetter._
import huffmanDecompressor._

class huffmanDecompressorCacheLineWrapper(params: huffmanParameters)
    extends Module {
  // This is how many bits are in a byte of the cache line
  val cacheLineBitsPerByte = 8
  // This is how many bytes are in a cache line.
  val cacheLineBytes = 64
  if (!((cacheLineBytes & (cacheLineBytes - 1)) == 0)) {
    print(
      "\n\n\nThe number of bytes in the cache line should be a power of two.\n\n\n"
    )

    System.exit(1)
  }
  // This is how many bits are in a line of the cache.
  val cacheLineBits = cacheLineBytes * cacheLineBitsPerByte
  // This is how many bits are used to address cache lines in the memory.
  val readPointerBits = log2Ceil(params.characters / cacheLineBytes)
  // This is how many iterations are needed to count all the characters in a cache line.
  val characterCountIterations =
    cacheLineBytes / params.characterFrequencyParallelism
  // This is how many bits needed to count the iterations.
  val iterationBits = log2Ceil(1 + characterCountIterations)
  // This is how many write ports need to be in the SyncReadMem
  val writePorts = params.characterFrequencyParallelism
  if (!(cacheLineBytes % params.characterFrequencyParallelism == 0)) {
    print(
      "\n\n\n[error] The number of bytes in a cache line must be a multiple of the characterFrequencyParallelism.\n\n\n"
    )

    System.exit(1)
  }

  val io =
    IO(new Bundle {
      val start = Input(Bool())

      // This is how the reads into memory are addressed.
      val readPointer = Output(UInt(readPointerBits.W))
      // This starts a read of the readPointer address.
      val loadReadPointer = Output(Bool())
      // This tells whether or not the read fifo is empty.
      val readFifoEmpty = Input(Bool())
      // This is set when the read fifo data is valid to move to the next read in the fifo.
      val readReady = Output(Bool())
      // This is set when the data requested from the FIFO is ready.
      val readValid = Input(Bool())
      // This is the data from the cache line.
      val readData = Input(UInt(cacheLineBits.W))

      // This lets the hardware know whether the write fifo can take anymore data.
      val writeFifoFull = Input(Bool())
      // This allows the hardware to request to write data to the memory.
      val writeRequest = Output(Bool())
      // This sets the data for the output cache line to be written.
      val writeData = Output(UInt(cacheLineBits.W))

      // This is set when the module is done decompressing. Once the decompression is done, done should be set
      // to 1 until the start signal goes low.
      val done = Output(Bool())
    })

  val numberOfStates = 3
  val stateBits = log2Ceil(numberOfStates)

  val waitingForStart :: decodingData :: waitingToReset :: Nil = Enum(
    numberOfStates
  )

  // This keeps track of the current state
  val state = RegInit(UInt(stateBits.W), waitingForStart)
  // This keeps track of how many iterations have been made in a given state.
  val iterations = RegInit(UInt(iterationBits.W), 0.U)

  val decompressor = Module(new huffmanDecompressor(params))
  decompressor.io.start <> io.start

  // This converts from the single-character-at-a-time representation of the compressor the the cache-line-level necessary to read and write to and from memory.
  val writeCacheLineConverter = Module(
    new bytesToCacheLine(
      inputDataBits = params.characterBits,
      outputDataBytes = cacheLineBytes,
      bitsPerByte = params.characterBits
    )
  )
  writeCacheLineConverter.io.outputData.bits <> io.writeData
  when(!io.writeFifoFull) {
    io.writeRequest := writeCacheLineConverter.io.outputData.valid
  }.otherwise {
    io.writeRequest := false.B
  }
  writeCacheLineConverter.io.outputData.ready := !io.writeFifoFull
  writeCacheLineConverter.io.inputData <> decompressor.io.dataOut(0)
  writeCacheLineConverter.io.inputDataLength := params.characterBits.U

  val readCacheLineConverter = Module(
    new cacheLineBitGetter(
      bitsPerByte = params.characterBits,
      bytesPerCacheLine = cacheLineBytes,
      bitsPerOutput = params.dictionaryEntryMaxBits,
      bytesInData = params.characters
    )
  )
  readCacheLineConverter.io.inputData.bits := io.readData
  readCacheLineConverter.io.inputData.valid <> io.readValid
  readCacheLineConverter.io.inputData.ready <> io.readReady
  // This makes sure that the ready valid interface of the cacheLineBitGetter is compatible
  // with the read fifo.
  when(io.readFifoEmpty) {
    io.loadReadPointer := readCacheLineConverter.io.readFifoPointer.valid
  }.otherwise {
    io.loadReadPointer := false.B
  }
  readCacheLineConverter.io.readFifoPointer.ready <> io.readFifoEmpty
  readCacheLineConverter.io.readFifoPointer.bits <> io.readPointer
  readCacheLineConverter.io.currentlyRequestedBit <> decompressor.io.currentBit(
    0
  )
  readCacheLineConverter.io.outputData <> decompressor.io.dataIn(0)

  io.done := state === waitingToReset

  // These default values are sometimes overwritten in the state machine.
  writeCacheLineConverter.io.dumpBuffer := false.B

  switch(state) {
    is(waitingForStart) {
      when(io.start) {
        state := decodingData
      }
    }
    is(decodingData) {
      when(decompressor.io.finished) {
        when(writeCacheLineConverter.io.currentBufferBits =/= 0.U) {
          // The final data needs to be dumped and written from the writeCacheLineConverter.
          writeCacheLineConverter.io.dumpBuffer := true.B
        }.otherwise {
          state := waitingToReset
        }
      }.otherwise {
        // The data is still being decompressed.
      }
    }
    is(waitingToReset) {
      io.done := true.B
      when(!io.start) {
        state := waitingForStart
      }
    }
  }
}

object huffmanDecompressorCacheLineWrapper extends App {
  val settingsGetter = new getHuffmanFromCSV()
  chisel3.Driver
    .execute(Array[String](), () => new huffmanDecompressorCacheLineWrapper(settingsGetter.getHuffmanFromCSV("configFiles/huffmanDecompressorCacheLineWrapper.csv")))
}
