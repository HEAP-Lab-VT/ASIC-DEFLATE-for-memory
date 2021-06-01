package lz77InputsAndOutputs

import chisel3._
import chisel3.util._
import lz77Parameters._

class compressorOutputs(params: lz77Parameters) extends Bundle {
  // This is the number of characters-worth of data being output.
  val length = Output(UInt(params.encodingLengthBits.W))
  // This is the output characters.
  val characters = Output(Vec(params.maxEncodingCharacterWidths, UInt(params.characterBits.W)))
  override def cloneType =
    (new compressorOutputs(params)).asInstanceOf[this.type]
}

class decompressorInputs(params: lz77Parameters) extends Bundle {
  val valid = Input(Bool())
  val ready = Output(Bool())
  // This is the number of characters that were read. We advance the read head this many characters for the next cycle.
  val charactersRead = Output(UInt(params.encodingLengthBits.W))
  // This is the output characters.
  val characters = Input(Vec(params.maxEncodingCharacterWidths, UInt(params.characterBits.W)))
  override def cloneType =
    (new decompressorInputs(params)).asInstanceOf[this.type]
}

class decompressorOutputs(params: lz77Parameters) extends Bundle {
  // This is the number of characters-worth of data being output.
  val length = Output(UInt(params.decompressorCharactersOutBits.W))
  // This is the output characters.
  val characters = Output(Vec(params.decompressorMaxCharactersOut, UInt(params.characterBits.W)))
  override def cloneType =
    (new decompressorOutputs(params)).asInstanceOf[this.type]
}

class patternMatch(params: lz77Parameters) extends Bundle {
  // This is the index of the first byte of the pattern in the CAM.
  val patternIndex = Output(UInt(params.camAddressBits.W))
  // This is how many bytes long the pattern that was recognized was.
  val length = Output(UInt(params.camCharacterSequenceLengthBits.W))

  override def cloneType =
    (new patternMatch(params)).asInstanceOf[this.type]
}

class searchInputs(params: lz77Parameters) extends Bundle {
  // This is the pattern of bits that should be recognized. The first byte to recognize always starts at index 0.
  val pattern = Output(Vec(params.camMaxPatternLength, UInt(params.characterBits.W)))
  // This is how many bytes long the pattern to be recognized is.
  val length = Output(UInt(params.camCharacterSequenceLengthBits.W))
  // This is the index of the current byte of the compressor. Any data at or past this index can't be counted by current
  // hardware as a match, so matches past this index are excluded.
  val currentCompressorIndex = Output(UInt(params.characterCountBits.W))

  override def cloneType =
    (new searchInputs(params)).asInstanceOf[this.type]
}
