package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.PrintWriter
import java.nio.file.Path
import scala.util.Using

class Parameters(
  characterBitsParam: Int,
  characterSpaceParam: Int,
  codeCountParam: Int,
  maxCodeLengthParam: Int,
  compressorCharsInParam: Int,
  compressorBitsOutParam: Int,
  counterCharsInParam: Int,
  encoderParallelismParam: Int,
  passOneSizeParam: Int,
  decompressorBitsInParam: Int,
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
  
  
  //============================================================================
  // COMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the compressor (in characters)
  val compressorCharsIn = compressorCharsInParam
  
  // output bus width of one way of the compressor (in characters)
  val compressorBitsOut = compressorBitsOutParam
  
  // bus width of the character frequency counter i.e. the first pass
  val counterCharsIn = counterCharsInParam
  
  val encoderParallelism = encoderParallelismParam
  
  // limit on the number of characters to count during the first pass
  val passOneSize = passOneSizeParam
  
  
  //============================================================================
  // DECOMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of one way of the decompressor (in characters)
  val decompressorBitsIn = decompressorBitsInParam
  
  // output bus width of the decompressor (in characters)
  val decompressorCharsOut = decompressorCharsOutParam
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(characterBits < 1)
    throw new IllegalArgumentException(
      s"characterBits: ${characterBits}")
  
  if(maxCodeLength < 1)
    throw new IllegalArgumentException(
      s"maxCodeLength: ${maxCodeLength}")
  
  if(codeCount < 1)
    throw new IllegalArgumentException(
      s"codeCount: ${codeCount}")
  
  if(counterCharsIn < 1)
    throw new IllegalArgumentException(
      s"counterCharsIn: ${counterCharsIn}")
  
  if(compressorCharsIn < 1)
    throw new IllegalArgumentException(
      s"compressorCharsIn: ${compressorCharsIn}")
  
  if(compressorBitsOut < 1)
    throw new IllegalArgumentException(
      s"compressorBitsOut: ${compressorBitsOut}")
  
  if(compressorBitsOut < maxCodeLength.valBits + characterBits + maxCodeLength)
    // a metadata chunk does not fit in output
    throw new IllegalArgumentException(
      s"compressorBitsOut: ${compressorBitsOut}")
  
  if(decompressorBitsIn < maxCodeLength + characterBits)
    // deadlock
    throw new IllegalArgumentException(
      s"decompressorBitsIn: ${decompressorBitsIn}")
  
  if(decompressorBitsIn < characterBits + maxCodeLength +
      maxCodeLength.valBits)
    // cannot load metadata
    throw new IllegalArgumentException(
      s"decompressorBitsIn: ${decompressorBitsIn}")
  
  if(decompressorCharsOut < 1)
    throw new IllegalArgumentException(
      s"decompressorCharsOut: ${decompressorCharsOut}")
  
  
  //============================================================================
  // METHODS
  //----------------------------------------------------------------------------
  
  def generateCppDefines(sink: PrintWriter, prefix: String = "",
    conditional: Boolean = false):
  Unit = {
    def define(name: String, definition: Any): Unit = {
      if(conditional)
      sink.println(s"#ifndef $prefix$name")
      sink.println(s"#define $prefix$name $definition")
      if(conditional)
      sink.println(s"#endif")
    }
    
    define("CHARACTER_BITS", characterBits)
    define("CHARACTER_SPACE", characterSpace)
    define("CODE_COUNT", codeCount)
    define("MAX_CODE_LENGTH", maxCodeLength)
    define("COMPRESSOR_CHARS_IN", compressorCharsIn)
    define("COMPRESSOR_BITS_OUT", compressorBitsOut)
    define("COUNTER_CHARS_IN", counterCharsIn)
    define("ENCODER_PARALLELISM", encoderParallelism)
    define("PASS_ONE_SIZE", passOneSize)
    define("DECOMPRESSOR_BITS_IN", decompressorBitsIn)
    define("DECOMPRESSOR_CHARS_OUT", decompressorCharsOut)
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
    counterCharsIn: Int,
    encoderParallelism: Int,
    passOneSize: Int,
    decompressorBitsIn: Int,
    decompressorCharsOut: Int
  ): Parameters =
    new Parameters(
      characterBitsParam = characterBits,
      characterSpaceParam = characterSpace,
      codeCountParam = codeCount,
      maxCodeLengthParam = maxCodeLength,
      compressorCharsInParam = compressorCharsIn,
      compressorBitsOutParam = compressorBitsOut,
      counterCharsInParam = counterCharsIn,
      encoderParallelismParam = encoderParallelism,
      passOneSizeParam = passOneSize,
      decompressorBitsInParam = decompressorBitsIn,
      decompressorCharsOutParam = decompressorCharsOut
    )
  
  def fromCSV(csvPath: Path): Parameters = {
    System.err.println(s"getting huffman parameters from $csvPath...")
    var map: Map[String, String] = Map()
    Using(io.Source.fromFile(csvPath.toFile())){lines =>
      for (line <- lines.getLines) {
        val cols = line.split(",").map(_.trim)
        if (cols.length == 2) {
          System.err.println(s"${cols(0)} = ${cols(1)}")
          map += (cols(0) -> cols(1))
        } else if (cols.length != 0) {
          System.err.println("Error: Each line must have exactly two values " +
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
      counterCharsInParam = map("counterCharsIn").toInt,
      encoderParallelismParam = map("encoderParallelism").toInt,
      passOneSizeParam = map("passOneSize").toInt,
      decompressorBitsInParam = map("decompressorBitsIn").toInt,
      decompressorCharsOutParam = map("decompressorCharsOut").toInt
    )
      
    System.err.println(s"finished getting huffman parameters from $csvPath.")
    return params
  }
}
