package edu.vt.cs.hardware_compressor.lz77

import chisel3._
import chisel3.util._
import Parameters._

class Parameters(
    characterBitsParam: Int = 8,
    compressorCharsInParam: Int = 11,
    compressorCharsOutParam: Int = 8,
    decompressorCharsInParam: Int = 8,
    decompressorCharsOutParam: Int = 8,
    camSizeParam: Int = 1016,
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
  
  // input bus width of the CAM
  val camCharsIn = compressorCharsIn // not configurable without buffer
  
  val camLookahead = minCharsToEncodeParam - 1
  
  // number of characters processed by the cam every cycle
  // (bus width - lookahead)
  val camCharsPerCycle = camCharsIn - camLookahead
  
  // size of the CAM buffer (including space for erroneous writes)
  val camBufSize = (camSize + camCharsPerCycle).ceilPow2.intValue
  
  
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
    ((characterBits + 1 + camSize.idxBits) / characterBits) + 1
  val minEncodingBits = minEncodingChars * characterBits
  
  // bits in min-encoding used to identify the length of the sequence
  val minEncodingLengthBits =
    minEncodingBits - characterBits - 1 - camSize.idxBits
  
  // additional sequence characters that can be described with one additional
  // character to the encoding
  val extraCharacterLengthIncrease = characterBits.space.intValue - 1
  
  // maximum characters that can be in a sequence of the minimum encoding length
  val maxCharsInMinEncoding =
    minCharsToEncode + minEncodingLengthBits.maxVal.intValue - 1
  
  
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
  
  if(!camBufSize.isPow2)
    // may require more complex logic including dividers
    System.err.println(s"warning: CAM buffer size not a power of 2" +
      " ($camBufSize); may cause decreased performance")
  
  if(camBufSize > camSize + camCharsPerCycle)
    // CAM buffer is larger than necessary
    System.err.println("warning: CAM buffer not fully utilized." +
      s" (${camSize + camCharsPerCycle} of $camBufSize elements utilized.)")
  
  if(camBufSize < camSize + camCharsPerCycle)
    // CAM buffer is too small
    throw new IllegalArgumentException(
      "CAM buffer too small")
}

object Parameters {
  
  def apply(
      characterBits: Int = 8,
      compressorCharsIn: Int = 11,
      compressorCharsOut: Int = 8,
      decompressorCharsIn: Int = 8,
      decompressorCharsOut: Int = 8,
      camSize: Int = 4088,
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
        System.err.println(s"${cols(0)} = ${cols(1)}")
        if (cols(1) == "true" || cols(1) == "false") {
          boolMap += (cols(0) -> (cols(1) == "true"))
        } else {
          intMap += (cols(0) -> cols(1).toInt)
        }
      } else if (cols.length != 0) {
        System.err.println("Error, each line should have two values separated by a comma."+
          " The line:\n")
        System.err.println(line)
        System.err.println("\nDid notmeet this requirement")
      }
    }
    file.close

    System.err.println("Getting from CSV test was successful")
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
    def isPow2(): Boolean = v == v.ceilPow2
    def ceilPow2(): BigInt = v.idxBits.space
  }
  implicit class widthOpsInt(v: Int) extends widthOpsBigInt(v)
  implicit class widthOpsLong(v: Long) extends widthOpsBigInt(v)
  implicit class widthOpsUInt(v: UInt) {
    def maxVal(): BigInt = (1 << v.getWidth) - 1
    def space(): BigInt = 1 << v.getWidth
  }
}
