package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    cHuffmanParam: huffmanParameters.huffmanParameters
) {
  
  //============================================================================
  // SUB-MODULE PARAMETERS
  //----------------------------------------------------------------------------
  
  // These are the parameters for the wrapped huffman module from Chandler
  // Most of the other parameters should be based on these.
  val cHuffman = cHuffmanParam
  
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  // size of a uncompressed character (in bits)
  val characterBits = characterBitsParam
  
  // Size of a unit of compressed bits
  val compressedCharBits = 1
  
  // This is equal to the number of ways -- one channel per way
  val channelCount: Int
  
  
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
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(characterBits <= 0)
    // altogether doesn't make sense
    throw new IllegalArgumentException(
      s"characterBits: ${characterBits}")
  
  if(compressedCharBits <= 0)
    // altogether doesn't make sense
    throw new IllegalArgumentException(
      s"compressedCharBits: ${compressedCharBits}")
}

object Parameters {
  
  def apply(
    cHuffman: huffmanParameters.huffmanParameters): Parameters =
    new Parameters(cHuffmanParam = cHuffman)
  
  def fromCSV(csvPath: String): Parameters = {
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
      cHuffmanParam = huffmanParameters.getHuffmanFromCSV
        .getHuffmanFromCSV(map("sub-huffman")))
      
    System.err.println("Getting from CSV was successful")
    return params
  }
}
