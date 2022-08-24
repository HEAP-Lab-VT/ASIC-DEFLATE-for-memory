package edu.vt.cs.hardware_compressor.lz

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.PrintWriter
import java.nio.file.Path
import scala.collection.{mutable, Map, SortedMap}
import scala.util.Using

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
  
  // character that is used to escape a LZ encoding
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
  
  if(minCharsToEncode < 1)
    // must encode at least one character
    throw new IllegalArgumentException(
      "must encode at least one character")
  
  
  //============================================================================
  // PRINTING
  //----------------------------------------------------------------------------
  
  lazy val map: Map[String, Any] = SortedMap(
    "characterBits" -> characterBits,
    "compressorCharsIn" -> compressorCharsIn,
    "compressorCharsOut" -> compressorCharsOut,
    "decompressorCharsIn" -> decompressorCharsIn,
    "decompressorCharsOut" -> decompressorCharsOut,
    "camSize" -> camSize,
    "camCharsIn" -> camCharsIn,
    "camLookahead" -> camLookahead,
    "camCharsPerCycle" -> camCharsPerCycle,
    "camBufSize" -> camBufSize,
    "escapeCharacter" -> escapeCharacter,
    "minCharsToEncode" -> minCharsToEncode,
    "maxCharsToEncode" -> maxCharsToEncode,
    "minEncodingChars" -> minEncodingChars,
    "minEncodingBits" -> minEncodingBits,
    "minEncodingLengthBits" -> minEncodingLengthBits,
    "extraCharacterLengthIncrease" -> extraCharacterLengthIncrease,
    "maxCharsInMinEncoding" -> maxCharsInMinEncoding
  )
  
  def print(sink: PrintWriter = new PrintWriter(System.out, true)): Unit = {
    map.foreachEntry{(name, value) =>
      sink.println(s"$name = $value")
    }
  }
  
  def genCppDefines(sink: PrintWriter, prefix: String = "",
    conditional: Boolean = false
  ): Unit = {
    map.foreachEntry{(name, value) =>
      val dispName = name
        .replaceAll("\\B[A-Z]", "_$0")
        .toUpperCase
        .prependedAll(prefix)
      if(conditional)
      sink.println(s"#ifndef $dispName")
      sink.println(s"#define $dispName $value")
      if(conditional)
      sink.println(s"#endif")
    }
  }
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
  
  def fromCSV(csvPath: Path): Parameters = {
    var map: mutable.Map[String, String] = mutable.Map.empty
    Using(io.Source.fromFile(csvPath.toFile())){lines =>
      for (line <- lines.getLines()) {
        val cols = line.split(",").map(_.trim)
        if (cols.length == 2) {
          map += (cols(0) -> cols(1))
        } else if (cols.length != 0) {
          System.err.println("Warning: " +
            "Each line must have exactly two values " +
            "separated by a comma.\n" +
            s"The line\n$line\ndoes not meet this requirement.")
        }
      }
    }

    val lzParametersOutput = new Parameters(
      characterBitsParam = map("characterBits").toInt,
      compressorCharsInParam = map("compressorCharsIn").toInt,
      compressorCharsOutParam = map("compressorCharsOut").toInt,
      decompressorCharsInParam = map("decompressorCharsIn").toInt,
      decompressorCharsOutParam = map("decompressorCharsOut").toInt,
      camSizeParam = map("camSize").toInt,
      escapeCharacterParam = map("escapeCharacter").toInt,
      minCharsToEncodeParam = map("minCharsToEncode").toInt,
      maxCharsToEncodeParam = map("maxCharsToEncode").toInt
    )
    return lzParametersOutput
  }
}
