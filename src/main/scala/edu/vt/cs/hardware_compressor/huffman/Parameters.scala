package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    huffmanParam: huffmanParameters.huffmanParameters
) {
  
  //============================================================================
  // SUB-MODULE PARAMETERS
  //----------------------------------------------------------------------------
  
  // These are the parameters for the wrapped huffman module from Chandler
  // Most of the other parameters should be based on these.
  val huffman = huffmanParam
  
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  // size of a uncompressed character (in bits)
  val characterBits = huffman.characterBits
  
  // Size of a unit of compressed bits
  val compressedCharBits = 1
  
  // number of ways -- one channel per way
  val channelCount = huffman.compressionParallelism
  
  // the maximum number of characters that can be handled by the sub-module
  val maxCompressionLimit = huffman.characters
  
  
  //============================================================================
  // COUNTER PARAMETERS
  //----------------------------------------------------------------------------
  
  // bus width of the character frequency counter i.e. the first pass
  val counterCharsIn = huffman.characterFrequencyParallelism
  
  
  //============================================================================
  // COMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of the compressor (in characters)
  val compressorCharsIn = huffman.compressionParallelism
  
  // output bus width of one way of the compressor (in characters)
  val compressorCharsOut = huffman.dictionaryEntryMaxBits
  
  
  //============================================================================
  // DECOMPRESSOR PARAMETERS
  //----------------------------------------------------------------------------
  
  // input bus width of one way of the decompressor (in characters)
  val decompressorCharsIn = huffman.decompressorInputBits
  
  // output bus width of the decompressor (in characters)
  val decompressorCharsOut = huffman.compressionParallelism
  
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
    huffman: huffmanParameters.huffmanParameters): Parameters =
    new Parameters(huffmanParam = huffman)
  
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
      huffmanParam = new huffmanParameters.getHuffmanFromCSV()
        .getHuffmanFromCSV(map("sub-huffman")))
      
    System.err.println(s"finished getting huffman parameters from $csvPath.")
    return params
  }
}
