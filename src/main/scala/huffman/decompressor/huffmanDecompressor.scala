// See README.md for license details.

package huffmanDecompressor

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

// Note, this needs to be named "decompressorWrapped" to work with the things in the huffman tree
class huffmanDecompressor(params: huffmanParameters) extends Module {
  // This function creates the hardware necessary to test if a given member of the huffman tree is equal to the input data.
  def treeCharacterEqualsInput(
      inputData: UInt,
      huffmanTreeCharacter: UInt,
      characterLength: UInt
  ): Bool = {
    // These are the masked versions of the input to select between when comparing to the tree character.
    val inputDataVariations = Wire(
      Vec(params.decompressorInputBits, UInt((params.decompressorInputBits).W))
    )
    for (index <- 0 until params.decompressorInputBits) {
      inputDataVariations(index) := inputData & ((~0.U(
        params.decompressorInputBits.W
      )) << (params.decompressorInputBits - index - 1))
    }
    val output = Wire(Bool())
    when(characterLength === 0.U) {
      output := false.B
    }.otherwise {
      output := inputDataVariations(characterLength - 1.U) === ((huffmanTreeCharacter) << (params.decompressorInputBits.U - characterLength))
    }

    // Scala doesn't have a "return" call, it just returns the result of the last line of the function, so it will return this Bool wire.
    output
  }

  val io = IO(new Bundle {
    val start = Input(Bool())
    val dataIn = Vec(
      params.compressionParallelism,
      Flipped(Decoupled(UInt(params.decompressorInputBits.W)))
    )
    val inReady = Output(
      Vec(
        params.compressionParallelism,
        UInt(log2Ceil(params.decompressorInputBits + 1).W)
      )
    )
    val dataOut = Vec(
      params.compressionParallelism,
      Decoupled(UInt(params.characterBits.W))
    )
    val finished = Output(Bool())
  })

  val numberOfStates = 4
  // The compression length state is only used if variable compression is enabled.
  val waiting :: loadingCompressionLength :: loadingMetadata :: decompressing :: Nil =
    Enum(numberOfStates)
  val state = RegInit(UInt(log2Ceil(numberOfStates + 1).W), waiting)

  val unencodedCharacters = Reg(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  )
  val encodedCharacters = Reg(
    Vec(params.huffmanTreeCharacters, UInt(params.codewordMaxBits.W))
  )
  val encodedLength = Reg(
    Vec(params.huffmanTreeCharacters, UInt(params.codewordLengthMaxBits.W))
  )
  val isEscape = unencodedCharacters
    .map(_(params.characterRepresentationBits - 1))

  // This will help to choose which tree character matches the input
  val matchingEncoding = Wire(
    Vec(
      params.compressionParallelism,
      Vec(params.huffmanTreeCharacters, Bool())
    )
  )

  // If variable compression is enabled, this holds the number of bytes to be decompressed.
  val compressionLimit =
    if (params.variableCompression)
      Some(RegInit(UInt(params.inputCharacterBits.W), 0.U))
    else None

  for (parallelIndex <- 0 until params.compressionParallelism) {
    for (huffmanIndex <- 0 until params.huffmanTreeCharacters) {
      matchingEncoding(parallelIndex)(huffmanIndex) := treeCharacterEqualsInput(
        io.dataIn(parallelIndex).bits,
        encodedCharacters(huffmanIndex),
        encodedLength(huffmanIndex)
      )
    }
  }

  // This will help to choose what character to output once the match is known
  val matchingCharacter = Wire(
    Vec(
      params.compressionParallelism,
      Vec(params.huffmanTreeCharacters, UInt(params.characterBits.W))
    )
  )
  for (parallelIndex <- 0 until params.compressionParallelism) {
    for (huffmanIndex <- 0 until params.huffmanTreeCharacters) {
      when(
        unencodedCharacters(huffmanIndex) === (1.U << (params.characterRepresentationBits - 1))
      ) {
        // This handles escape characters.
        matchingCharacter(parallelIndex)(huffmanIndex) := io
          .dataIn(parallelIndex)
          .bits >> (params.decompressorInputBits.U - params.characterBits.U - encodedLength(
          huffmanIndex
        ))
      }.otherwise {
        // This handles standard characters.
        matchingCharacter(parallelIndex)(huffmanIndex) := unencodedCharacters(
          huffmanIndex
        )
      }
    }
  }

  // Setting defaults
  for (index <- 0 until params.compressionParallelism) {
    io.dataOut(index).valid := false.B
    io.dataOut(index).bits := 0.U
    io.dataIn(index).ready := false.B
    io.inReady(index) := 0.U
  }

  switch(state) {
    is(waiting) {
      when(io.start === true.B) {
        state := loadingMetadata
      }
    }

    is(loadingMetadata) {
      io.dataIn(0).ready := true.B
      val iterations =
        Seq(RegInit(UInt(log2Ceil(params.huffmanTreeCharacters).W), 0.U))
      when(io.dataIn(0).valid) {
        unencodedCharacters(iterations(0)) := io
          .dataIn(0)
          .bits(
            params.decompressorInputBits - 1,
            params.decompressorInputBits - params.characterRepresentationBits
          )
        encodedCharacters(iterations(0)) := io
          .dataIn(0)
          .bits(
            params.decompressorInputBits - params.characterRepresentationBits - 1,
            params.decompressorInputBits - params.characterRepresentationBits - params.codewordMaxBits
          )
        encodedLength(iterations(0)) := io
          .dataIn(0)
          .bits(
            params.decompressorInputBits - params.characterRepresentationBits - params.codewordMaxBits - 1,
            params.decompressorInputBits - params.characterRepresentationBits - params.codewordMaxBits - params.codewordLengthMaxBits
          )

        io.inReady(0) := params.decompressorInputBits.U
        when(when.cond){iterations(0) := iterations(0) + 1.U}
        when(iterations(0) === (params.huffmanTreeCharacters - 1).U) {
          state := decompressing
        }
      }
    }

    is(decompressing) {
      // This generates the hardware once for each of the parallel compressors. If all their data is
      // valid, they output the necessary data and go to the next iteration.
      for (index <- 0 until params.compressionParallelism) {
        io.dataIn(index).ready := io.dataOut(index).ready
        io.dataOut(index).valid := io.dataIn(index).valid
        io.dataOut(index).bits := Mux1H(
          matchingEncoding(index),
          matchingCharacter(index)
        )
        when(io.dataOut(index).ready) {
          io.inReady(index) := Mux1H(
            matchingEncoding(index),
            encodedLength
              .zip(isEscape.map(e => Mux(e, params.characterBits.U, 0.U)))
              .map(l => l._1 +& l._2)
          )
        } otherwise {
          io.inReady(index) := 0.U
        }
      }
    }
  }
  io.finished := state === waiting
}

object huffmanDecompressor extends App {
  val settingsGetter = new getHuffmanFromCSV()
  chisel3.Driver
    .execute(
      Array[String](),
      () =>
        new huffmanDecompressor(
          settingsGetter.getHuffmanFromCSV("configFiles/huffmanCompressorDecompressor.csv")
        )
    )
}
