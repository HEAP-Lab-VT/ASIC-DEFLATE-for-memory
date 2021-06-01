package huffmanCompressorCacheLineWrapper

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._
import huffmanCompressor._
import bytesToCacheLine._

class huffmanCompressorCacheLineWrapper(params: huffmanParameters)
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
  // This is how many bits are in a cache line.
  val cacheLineBits = cacheLineBytes * cacheLineBitsPerByte
  // This is how many bits are used to address cache lines in the memory.
  val readPointerBits = log2Ceil(params.characters / cacheLineBytes)
  // This is how many bits are needed to show the size of the compressed data. This is
  // defined by HALK.
  val compressionSizeBits = 14
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

      // This is the number of bytes in the compressed representation. This must be a multiple of the number of bytes in a cache line.
      val compressedSize = Output(UInt(compressionSizeBits.W))
      // This flag is set if the input data can't be compressed.
      val incompressible = Output(Bool())
      // This is set when the module is done compressing. Once the compression is done, done should be set
      // to 1 until the start signal goes low.
      val done = Output(Bool())
    })

  val numberOfStates = 6
  val stateBits = log2Ceil(numberOfStates)

  // This converts the readData input into a Vec of bytes.
  val readDataAsFlattenedVec = Wire(
    Vec(cacheLineBytes, Vec(cacheLineBitsPerByte, Bool()))
  )
  val readDataAsVec = Wire(Vec(cacheLineBytes, UInt(cacheLineBitsPerByte.W)))
  for (firstIndex <- 0 until cacheLineBytes) {
    for (secondIndex <- 0 until cacheLineBitsPerByte) {
      readDataAsFlattenedVec(cacheLineBytes - 1 - firstIndex)(secondIndex) := io.readData
        .asBools()(firstIndex * cacheLineBitsPerByte + secondIndex)
    }
    readDataAsVec(firstIndex) := readDataAsFlattenedVec(firstIndex).asUInt()
  }

  // This is used to cache the input that is read by the character Frequency counter
  // so the data doesn't need to be accessed a second time to be encoded.
  val inputDataCache =
    SyncReadMem(params.characters, UInt(cacheLineBitsPerByte.W))
  // This stores the most recent access from the inputDataCache.
  val inputDataCacheByte = Reg(UInt(cacheLineBitsPerByte.W))

  // This is the different state values for the state machine.
  val waitingForStart :: requestingMoreCharacters :: countingCacheLineCharacters :: encodingDataRequestDataFromCache :: encodingDataCacheDataReceived :: waitingToReset :: Nil =
    Enum(numberOfStates)

  // This keeps track of the current state
  val state = RegInit(UInt(stateBits.W), waitingForStart)
  // This keeps track of how many iterations have been made in a given state.
  val iterations = RegInit(UInt(iterationBits.W), 0.U)

  // This keeps track of how many bytes the compressed version of the data takes up. It needs to be a multiple of the cache line bytes number.
  val compressedSize = RegInit(UInt(compressionSizeBits.W), 0.U)

  val compressor = Module(new huffmanCompressor(params))
  compressor.io.start <> io.start

  // This converts from the single-character-at-a-time representation of the compressor the the cache-line-level necessary to read and write to and from memory.
  val writeCacheLineConverter = Module(
    new bytesToCacheLine(
      inputDataBits = params.dictionaryEntryMaxBits,
      outputDataBytes = cacheLineBytes,
      bitsPerByte = params.characterBits
    )
  )
  writeCacheLineConverter.io.outputData.bits <> io.writeData
  writeCacheLineConverter.io.inputData.bits <> compressor.io.outputs(0).dataOut
  writeCacheLineConverter.io.inputData.ready <> compressor.io.outputs(0).ready
  writeCacheLineConverter.io.inputData.valid <> compressor.io.outputs(0).valid
  writeCacheLineConverter.io.inputDataLength <> compressor.io
    .outputs(0)
    .dataLength

  // This is used to store the read cache line when it is made available.
  val readCacheLineStoredValid = RegInit(Bool(), false.B)
  val readCacheLineStored = Reg(UInt(cacheLineBits.W))

  io.done := state === waitingToReset
  io.compressedSize := compressedSize

  // TODO: This probably needs to be fixed at some point to actually mean something, but you only really know if it's incompressible at the end of the compression.
  io.incompressible := false.B

  // These default values are sometimes overwritten in the state machine.
  io.readReady := false.B
  io.readPointer := 0.U
  io.loadReadPointer := false.B
  writeCacheLineConverter.io.outputData.ready := false.B
  writeCacheLineConverter.io.dumpBuffer := false.B
  io.writeRequest := false.B
  compressor.io.characterFrequencyInputs.valid := false.B
  compressor.io.compressionInputs(0).dataIn(0) := 0.U
  compressor.io.compressionInputs(0).valid := false.B
  for (index <- 0 until params.characterFrequencyParallelism) {
    compressor.io.characterFrequencyInputs.dataIn(index) := 0.U
  }

  switch(state) {
    is(waitingForStart) {
      compressedSize := 0.U
      when(io.start) {
        state := requestingMoreCharacters
      }
    }
    is(requestingMoreCharacters) {
      iterations := 0.U
      io.readPointer := compressor.io.characterFrequencyInputs.currentByteOut / cacheLineBytes.U
      when(!compressor.io.compressionInputs(0).ready) {
        when(compressor.io.characterFrequencyInputs.ready) {
          when(io.readFifoEmpty) {
            // This submits a read request, then transitions to the state to take in the read data.
            io.loadReadPointer := true.B
            state := countingCacheLineCharacters
            io.readReady := true.B
            readCacheLineStoredValid := false.B
          }
        }
      }.otherwise {
        // All the characters have been counted, so move on to the encoding itself.
        state := encodingDataRequestDataFromCache
      }
    }
    is(countingCacheLineCharacters) {
      io.readReady := true.B
      when(io.readValid) {
        readCacheLineStored := io.readData
        readCacheLineStoredValid := true.B
      }
      when(readCacheLineStoredValid === true.B) {
        // This reads from the cache line while the data is valid.
        when(iterations >= characterCountIterations.U) {
          state := requestingMoreCharacters
        }.otherwise {
          compressor.io.characterFrequencyInputs.valid := true.B
          when(compressor.io.characterFrequencyInputs.ready) {
            // When the character frequency inputs are ready to receive data, increment state info,
            // input the data, and write the data to the input data cache.
            iterations := iterations + 1.U
            for (index <- 0 until params.characterFrequencyParallelism) {
              val readDataIndex =
                index.U + iterations * params.characterFrequencyParallelism.U
              val inputDataCacheIndex =
                compressor.io.characterFrequencyInputs.currentByteOut + index.U
              // TODO: This is only set up to work with characterFrequencyInputs if it has no parallelism. This will need to be fixed, or will give incorrect characters and bad compression ratios for increased parallelism
              compressor.io.characterFrequencyInputs
                .dataIn(index) := (readCacheLineStored >> ((characterCountIterations.U - 1.U - (iterations)) * cacheLineBitsPerByte.U))(
                cacheLineBitsPerByte - 1,
                0
              )
              inputDataCache.write(
                inputDataCacheIndex,
                (readCacheLineStored >> ((characterCountIterations.U - 1.U - (iterations)) * cacheLineBitsPerByte.U))(
                  cacheLineBitsPerByte - 1,
                  0
                )
              )
            }
          }
        }
      }
    }
    is(encodingDataRequestDataFromCache) {
      // This will request data from the input data cache SRAM, then transition to the data received state where the data will be processed.
      when(compressor.io.finished) {
        // Once the compressor is done, this makes sure that all the data has been written out.
        when(writeCacheLineConverter.io.currentBufferBits > 0.U) {
          // If there is still data in the converter, dump it and write it when possible.
          writeCacheLineConverter.io.dumpBuffer := true.B
          when(writeCacheLineConverter.io.outputData.valid) {
            when(!io.writeFifoFull) {
              writeCacheLineConverter.io.outputData.ready := true.B
              io.writeRequest := true.B
            }
          }
        }.otherwise {
          // Progress to the state where everything is done once the bits remaining in the buffer are 0.
          state := waitingToReset
        }
      }.otherwise {
        when(writeCacheLineConverter.io.outputData.valid) {
          // This writes the current line of the cache to memory.
          when(!io.writeFifoFull) {
            writeCacheLineConverter.io.outputData.ready := true.B
            io.writeRequest := true.B
          }
        }.otherwise {
          when(compressor.io.compressionInputs(0).ready) {
            // This requests a byte of data from the cache, then receives it
            inputDataCacheByte := inputDataCache.read(
              compressor.io.compressionInputs(0).currentByteOut
            )
            state := encodingDataCacheDataReceived
          }
        }
      }
    }
    is(encodingDataCacheDataReceived) {
      compressor.io.compressionInputs(0).valid := true.B
      compressor.io.compressionInputs(0).dataIn(0) := inputDataCacheByte
      when(compressor.io.compressionInputs(0).ready) {
        state := encodingDataRequestDataFromCache
      }
    }
    is(waitingToReset) {
      io.done := true.B
      when(!io.start) {
        state := waitingForStart
      }
    }
  }

  when(io.writeRequest) {
    compressedSize := compressedSize + cacheLineBytes.U
  }
}

object huffmanCompressorCacheLineWrapper extends App {
  val settingsGetter = new getHuffmanFromCSV()
  chisel3.Driver
    .execute(Array[String](), () => new huffmanCompressorCacheLineWrapper(settingsGetter.getHuffmanFromCSV("configFiles/huffmanCompressorCacheLineWrapper.csv")))
}
