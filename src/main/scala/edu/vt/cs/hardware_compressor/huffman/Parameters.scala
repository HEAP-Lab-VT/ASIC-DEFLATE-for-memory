package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    decompressorBitsInParam: Int,
    decompressorCharsOutParam: Int
) {
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  // number of bits in an uncompressed character
  val characterBits = 8
  
  val characterSpace = characterBits.space.toInt
  
  // the number of huffman codes
  val codeCount = 16
  
  // maximum number of bits in a huffman code
  val maxCodeLength = 16
  
  
  //============================================================================
  // COMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the compressor (in characters)
  val compressorCharsIn = 8
  
  // output bus width of one way of the compressor (in characters)
  val compressorBitsOut = 32
  
  // bus width of the character frequency counter i.e. the first pass
  val counterCharsIn = compressorCharsIn
  
  val encoderParallelism = 8
  
  // maximum number of charaters to compress in a single run
  val maxInputSize = 8192
  
  // limit on the number of characters to count during the first pass
  val passOneSize = 4096
  
  
  //============================================================================
  // DECOMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of one way of the decompressor (in characters)
  val decompressorBitsIn = 32
  
  // output bus width of the decompressor (in characters)
  val decompressorCharsOut = 8
  
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
      s"codeCount: ${codeCount}")
  
  if(decompressorBitsIn < 1 + characterBits + maxCodeLength +
      maxCodeLength.valBits)
    // cannot load metadata
    throw new IllegalArgumentException(
      s"codeCount: ${codeCount}")
  
  if(decompressorCharsOut < 1)
    throw new IllegalArgumentException(
      s"codeCount: ${codeCount}")
}

object Parameters {
  
  def apply(
    decompressorBitsIn: Int,
    decompressorCharsOut: Int
  ): Parameters =
    new Parameters(
      decompressorBitsInParam = decompressorBitsIn,
      decompressorCharsOutParam = decompressorCharsOut
    )
  
  def fromCSV(csvPath: String): Parameters = {
    System.err.println(s"getting huffman parameters from $csvPath...")
    var map: Map[String, String] = Map()
    val file = io.Source.fromFile(csvPath)
    for (line <- file.getLines) {
      val cols = line.split(",").map(_.trim)
      if (cols.length == 2) {
        System.err.println(s"${cols(0)} = ${cols(1)}")
        map += (cols(0) -> cols(1))
      } else if (cols.length != 0) {
        System.err.println("Error: Each line should have exactly two values " +
          "separated by a comma.\n\n" +
          s"The line\n\n$line\n\ndid notmeet this requirement.")
      }
    }
    file.close
    
    val params = new Parameters(
      32, 8)
      
    System.err.println(s"finished getting huffman parameters from $csvPath.")
    return params
  }
}
