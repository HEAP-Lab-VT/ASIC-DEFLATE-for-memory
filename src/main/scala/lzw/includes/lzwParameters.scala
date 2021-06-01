package lzwParameters

import chisel3._
import chisel3.util._

class lzwParameters(
    characterBitsParam: Int,
    maxCharacterSequenceParam: Int,
    dictionaryItemsMaxParam: Int,
    debugStatisticsParam: Boolean,
    debugStatisticsSequenceLengthBitsParam: Int
) {
  // Defined parameters

  // This is the number of bits in a single character of the input.
  var characterBits = characterBitsParam
  // This is the maximum number of characters in a row that a single entry in the dictionary can store.
  var maxCharacterSequence = maxCharacterSequenceParam
  // This is the number of entries in the dictionary that can represent more than 1 character.
  var dictionaryItemsMax = dictionaryItemsMaxParam
  // This controls whether or not the debug statistics are generated.
  var debugStatistics = debugStatisticsParam
  // This controls how many bits the debug statistics sequence length counters are.
  var debugStatisticsSequenceLengthBits = debugStatisticsSequenceLengthBitsParam

  // Derived parameters

  // This is the number of different values that can be represented with the number of character bits.
  var characterPossibilities = 1 << characterBits
  // This is the maximum number of bits in the encoding for an entry in the dictionary.
  var maxEncodingWidth = log2Ceil(dictionaryItemsMax + characterPossibilities)
  // This is the number of bits needed to store the number of bits of an encoding in the dictionary.
  var maxEncodingWidthBits = log2Ceil(maxEncodingWidth + 1)
  // This is the number of bits needed to store how many characters are represented by a given item in the dictionary.
  var maxCharacterSequenceBits = log2Ceil(maxCharacterSequence + 1)
  // This is the number of bits needed to index into the dictionary.
  var dictionaryAddressBits = log2Ceil(dictionaryItemsMax)
  // This is the number of bits needed to count how many items are in the dictionary.
  var dictionaryCountBits = log2Ceil(dictionaryItemsMax + 1)
  // This is the number of bits in the character buffer.
  var characterBufferBits = maxCharacterSequence * characterBits
  // This is the number of bits for a full dictionary entry.
  var dictionaryEntryBits = characterBufferBits + maxCharacterSequenceBits

}

class getLZWFromCSV() {
  // This takes a dictionary entry and gets the character bits from it.
  def getLZWFromCSV(csvFilepath: String): lzwParameters = {

    var boolMap: Map[String, Boolean] = Map()
    var intMap: Map[String, Int] = Map()
    val file = io.Source.fromFile(csvFilepath)
    for (line <- file.getLines) {
      val cols = line.split(",").map(_.trim)
      if (cols.length == 2) {
        println(s"${cols(0)} = ${cols(1)}")
        if (cols(1) == "true" || cols(1) == "false") {
          boolMap += (cols(0) -> (cols(1) == "true"))
        } else {
          intMap += (cols(0) -> cols(1).toInt)
        }
      } else if(cols.length != 0){
        println("Error, each line should have two values separated by a comma. The line:\n")
        println(line)
        println("\nDid notmeet this requirement")
      }
    }
    file.close

    println("Getting from CSV test was successful")
    val lzwParametersOutput = new lzwParameters(
    characterBitsParam = intMap("characterBits"),
    maxCharacterSequenceParam = intMap("maxCharacterSequence"),
    dictionaryItemsMaxParam = intMap("dictionaryItemsMax"),
    debugStatisticsParam = boolMap("debugStatistics"),
    debugStatisticsSequenceLengthBitsParam = intMap("debugStatisticsSequenceLengthBits")
    )

    return lzwParametersOutput
  }
}