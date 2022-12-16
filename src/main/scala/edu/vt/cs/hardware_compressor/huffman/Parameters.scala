package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.PrintWriter
import java.nio.file.Path
import scala.collection.{mutable, Map, SortedMap}
import scala.util.Using

class Parameters(
  characterBitsParam: Int,
  characterSpaceParam: Int,
  codeCountParam: Int,
  maxCodeLengthParam: Int,
  compressorCharsInParam: Int,
  compressorBitsOutParam: Int,
  encoderParallelismParam: Int,
  passOneSizeParam: Int,
  accRepBufferSizeParam: Int,
  decompressorLineBitsParam: Int,
  decompressorCharsOutParam: Int
) {
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  // number of bits in an uncompressed character
  val characterBits = characterBitsParam
  
  val characterSpace = characterSpaceParam
  
  // the number of huffman codes
  val codeCount = codeCountParam
  
  // maximum number of bits in a huffman code
  val maxCodeLength = maxCodeLengthParam
  
  val maxFullSymbolLength = maxCodeLengthParam + characterBits
  
  
  //============================================================================
  // COMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the compressor (in characters)
  val compressorCharsIn = compressorCharsInParam
  
  // output bus width of one way of the compressor (in characters)
  val compressorBitsOut = compressorBitsOutParam
  
  // bus width of the character frequency counter i.e. the first pass
  val counterCharsIn = compressorCharsInParam
  
  val encoderParallelism = encoderParallelismParam
  
  // limit on the number of characters to count during the first pass
  val passOneSize = passOneSizeParam
  
  val accRepBufferSize = accRepBufferSizeParam
  
  
  //============================================================================
  // DECOMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  val decompressorLineBits = decompressorLineBitsParam
  
  val decompressorLookahead = maxFullSymbolLength - 1
  
  // input bus width of one way of the decompressor (in characters)
  val decompressorBitsIn = decompressorLineBits + decompressorLookahead
  
  // output bus width of the decompressor (in characters)
  val decompressorCharsOut = decompressorCharsOutParam
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(passOneSize + 1 > accRepBufferSize)
    // accumulate-replay and the counter will deadlock
    throw new IllegalArgumentException(s"passOneSize (${passOneSize}) is too " +
      s"large for the specified accRepBufferSize (${accRepBufferSize}).")
  
  if(!accRepBufferSize.isPow2)
    // accumulate replay logic assumes it is a power of 2
    throw new IllegalArgumentException(s"accRepBufferSize " +
      s"(${accRepBufferSize}) is not a power of 2.")
  
  if(!counterCharsIn.isPow2)
    // accumulate replay logic assumes it is a power of 2
    throw new IllegalArgumentException(s"counterCharsIn " +
      s"(${counterCharsIn}) is not a power of 2.")
  
  if(!encoderParallelism.isPow2)
    // accumulate replay logic assumes it is a power of 2
    throw new IllegalArgumentException(s"accRepBufferSize " +
      s"(${encoderParallelism}) is not a power of 2.")
  
  
  //============================================================================
  // PRINTING
  //----------------------------------------------------------------------------
  
  lazy val map: Map[String, Any] = SortedMap(
    "characterBits" -> characterBits,
    "characterSpace" -> characterSpace,
    "codeCount" -> codeCount,
    "maxCodeLength" -> maxCodeLength,
    "maxFullSymbolLength" -> maxFullSymbolLength,
    "compressorCharsIn" -> compressorCharsIn,
    "compressorBitsOut" -> compressorBitsOut,
    "counterCharsIn" -> counterCharsIn,
    "encoderParallelism" -> encoderParallelism,
    "passOneSize" -> passOneSize,
    "accRepBufferSize" -> accRepBufferSize,
    "decompressorLineBits" -> decompressorLineBits,
    "decompressorLookahead" -> decompressorLookahead,
    "decompressorBitsIn" -> decompressorBitsIn,
    "decompressorCharsOut" -> decompressorCharsOut
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
    characterBits: Int,
    characterSpace: Int,
    codeCount: Int,
    maxCodeLength: Int,
    compressorCharsIn: Int,
    compressorBitsOut: Int,
    encoderParallelism: Int,
    passOneSize: Int,
    accRepBufferSize: Int,
    decompressorLineBits: Int,
    decompressorCharsOut: Int
  ): Parameters =
    new Parameters(
      characterBitsParam = characterBits,
      characterSpaceParam = characterSpace,
      codeCountParam = codeCount,
      maxCodeLengthParam = maxCodeLength,
      compressorCharsInParam = compressorCharsIn,
      compressorBitsOutParam = compressorBitsOut,
      encoderParallelismParam = encoderParallelism,
      passOneSizeParam = passOneSize,
      accRepBufferSizeParam = accRepBufferSize,
      decompressorLineBitsParam = decompressorLineBits,
      decompressorCharsOutParam = decompressorCharsOut
    )
  
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
    
    val params = new Parameters(
      characterBitsParam = map("characterBits").toInt,
      characterSpaceParam = map("characterSpace").toInt,
      codeCountParam = map("codeCount").toInt,
      maxCodeLengthParam = map("maxCodeLength").toInt,
      compressorCharsInParam = map("compressorCharsIn").toInt,
      compressorBitsOutParam = map("compressorBitsOut").toInt,
      encoderParallelismParam = map("encoderParallelism").toInt,
      passOneSizeParam = map("passOneSize").toInt,
      accRepBufferSizeParam = map("accRepBufferSize").toInt,
      decompressorLineBitsParam = map("decompressorLineBits").toInt,
      decompressorCharsOutParam = map("decompressorCharsOut").toInt
    )
    params
  }
}
