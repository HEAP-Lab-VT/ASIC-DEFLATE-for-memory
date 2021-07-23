package edu.vt.cs.hardware_compressor.lz77

import chisel3._
import chisel3.util._

class Parameters(
    characterBitsParam: Int = 8,
    compressorCharsInParam: Int = 8,
    compressorCharsOutParam: Int = 8,
    decompressorCharsInParam: Int = 8,
    decompressorCharsOutParam: Int = 8,
    camSizeParam: Int = 4096,
    escapeCharacterParam: Int = 103,
    minCharsToEncodeParam: Int = 4,
    maxCharsToEncodeParam: Int = 4095
) {
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  // size of a character (in bits)
  val characterBits = characterBitsParam
  
  
  //============================================================================
  // COMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the compressor (in characters)
  val compressorCharsIn = compressorCharsInParam
  
  // output bus width of the compressor (in characters)
  val compressorCharsOut = compressorCharsOutParam
  
  
  //============================================================================
  // DECOMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the decompressor (in characters)
  val decompressorCharsIn = decompressorCharsInParam
  
  // output bus width of the decompressor (in characters)
  val decompressorCharsOut = decompressorCharsOutParam
  
  
  //============================================================================
  // CAM PARAMETERS
  //----------------------------------------------------------------------------
  
  // size of the CAM (in characters)
  val camSize = camSizeParam
  
  // true iff the CAM size is a power of 2 (allows some wrapping optimizations)
  val camSizePow2 = camSize == 1 << log2Ceil(camSize)
  
  // input buss width of the CAM
  val camCharsIn = compressorCharsIn // not configurable without buffer
  
  // size of the history buffer (including space for erroneous writes)
  val historySize = camSize + camCharsIn
  
  // iff the history size is a power of 2 (allows some wrapping optimizations)
  val histSizePow2 = historySize == 1 << log2Ceil(historySize)
  
  
  //============================================================================
  // ENCODING PARAMETERS
  //----------------------------------------------------------------------------
  
  // character that is used to escape a LZ77 encoding
  val escapeCharacter = escapeCharacterParam
  
  // minimum number of characters to compress to an encoding
  val minCharsToEncode = minCharsToEncodeParam
  
  // maximum number of characters to compress to a single encoding
  val maxCharsToEncode = maxCharsToEncodeParam
  
  // size of shortest encoding: escape + confirmation + address + length
  val minEncodingChars =
    ((characterBits + 1 + log2Ceil(camSize)) / characterBits) + 1
  val minEncodingBits = minEncodingChars * characterBits
  
  // bits in min-encoding used to identify the length of the sequence
  val minEncodingLengthBits =
    minEncodingBits - characterBits - 1 - log2Ceil(camSize)
  
  // additional sequence characters that can be described with one additional
  // character to the encoding
  val extraCharacterLengthIncrease = (1 << characterBits) - 1
  
  // maximum characters that can be in a sequence of the minimum encoding length
  val maxCharsInMinEncoding =
    minCharsToEncode + (1 << minEncodingLengthBits) - 2
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(characterBits <= 0)
    // altogether doesn't make sense
    throw new IllegalArgumentException(
      s"characterBits: ${characterBits}")
  
  // if(camCharacters < 2)
  //   // idk
  //   throw new IllegalArgumentException("camCharacters cannot be less than 2")
  
  if(escapeCharacter >= (1 << characterBits) || escapeCharacter < 0)
    // out of range of character
    throw new IllegalArgumentException(
      "escapeCharacter not representable in a character")
  
  if(camCharsIn < minCharsToEncode)
    // may cause deadlock as CAM waits for enough literals to ensure non-match
    throw new IllegalArgumentException(
      "compressorCharsIn must be at least minCharsToEncode")
  
  if(decompressorCharsIn < minEncodingChars)
    // may cause deadlock as decompressor cannot see encoding header all at once
    throw new IllegalArgumentException(
      "decompressorCharsIn must be at least minEncodingChars")
  
  if(!histSizePow2)
    // may require more complex logic including dividers
    println("warning: " +
      "CAM history size not a power of 2; may cause decreased performance")
}

object Parameters {
  
  def apply(
      characterBits: Int = 8,
      compressorCharsIn: Int = 8,
      compressorCharsOut: Int = 8,
      decompressorCharsIn: Int = 8,
      decompressorCharsOut: Int = 8,
      camSize: Int = 4096,
      escapeCharacter: Int = 103,
      minCharsToEncode: Int = 4,
      maxCharsToEncode: Int = 4095): Parameters =
    new Parameters(
      characterBitsParam = characterBits,
      compressorCharsInParam = compressorCharsIn,
      compressorCharsOutParam = compressorCharsOut,
      decompressorCharsInParam = decompressorCharsIn,
      decompressorCharsOutParam = decompressorCharsOut,
      camSizeParam = camSize,
      escapeCharacterParam = escapeCharacter,
      minCharsToEncodeParam = minCharsToEncode,
      maxCharsToEncodeParam = maxCharsToEncode)
  
  def fromCSV(csvPath: String): Parameters = {
    var boolMap: Map[String, Boolean] = Map()
    var intMap: Map[String, Int] = Map()
    val file = io.Source.fromFile(csvPath)
    for (line <- file.getLines) {
      val cols = line.split(",").map(_.trim)
      if (cols.length == 2) {
        println(s"${cols(0)} = ${cols(1)}")
        if (cols(1) == "true" || cols(1) == "false") {
          boolMap += (cols(0) -> (cols(1) == "true"))
        } else {
          intMap += (cols(0) -> cols(1).toInt)
        }
      } else if (cols.length != 0) {
        println("Error, each line should have two values separated by a comma."+
          " The line:\n")
        println(line)
        println("\nDid notmeet this requirement")
      }
    }
    file.close

    println("Getting from CSV test was successful")
    val lz77ParametersOutput = new Parameters(
      characterBitsParam = intMap("characterBits"),
      compressorCharsInParam = intMap("compressorCharsIn"),
      compressorCharsOutParam = intMap("compressorCharsOut"),
      decompressorCharsInParam = intMap("decompressorCharsIn"),
      decompressorCharsOutParam = intMap("decompressorCharsOut"),
      camSizeParam = intMap("camSize"),
      escapeCharacterParam = intMap("escapeCharacter"),
      minCharsToEncodeParam = intMap("minCharsToEncode"),
      maxCharsToEncodeParam = intMap("maxCharsToEncode"))

    return lz77ParametersOutput
  }
  
  // some handy functions for interpreting parameters
  implicit class widthOpsBigInt(v: BigInt) {
    def valBits(): Int = log2Ceil(v + 1)
    def idxBits(): Int = log2Ceil(v)
    def valUInt(): UInt = UInt(v.valBits.W)
    def idxUInt(): UInt = UInt(v.idxBits.W)
    def maxVal(): BigInt = (BigInt(1) << v.intValue) - 1
    def space(): BigInt = BigInt(1) << v.intValue
  }
  implicit class widthOpsInt(v: Int) extends widthOpsBigInt(v)
  implicit class widthOpsLong(v: Long) extends widthOpsBigInt(v)
  implicit class widthOpsUInt(v: UInt) {
    def maxVal(): BigInt = (1 << v.getWidth) - 1
    def space(): BigInt = 1 << v.getWidth
  }
}
