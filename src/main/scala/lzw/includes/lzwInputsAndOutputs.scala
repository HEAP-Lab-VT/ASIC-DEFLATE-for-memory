package lzwInputsAndOutputs

import chisel3._
import chisel3.util._
import lzwParameters._

class lzwStatistics(params: lzwParameters) extends Bundle {
  // This the current number of dictionary entries used in the LZW dictionary.
  val dictionaryEntries = Output(UInt(params.dictionaryCountBits.W))
  // This is the length of the longest character sequence in the dictionary.
  val longestSequenceLength = Output(UInt(params.maxCharacterSequenceBits.W))
  // This is the number of times each length of character sequence is present in the output
  val sequenceLengths = Output(Vec(params.maxCharacterSequence, UInt(params.debugStatisticsSequenceLengthBits.W)))

  override def cloneType =
    (new lzwStatistics(params)).asInstanceOf[this.type]
}
