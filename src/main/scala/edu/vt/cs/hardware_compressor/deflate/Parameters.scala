package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    lzParam: edu.vt.cs.hardware_compressor.lz77.Parameters,
    huffmanParam: huffmanParameters.huffmanParameters
) {
  
  //============================================================================
  // SUB-MODULE PARAMETERS
  //----------------------------------------------------------------------------
  
  val lz = lzParam
  val huffman = huffmanParam
  
  
  //============================================================================
  // INPUT-OUTPUT PARAMETERS
  //----------------------------------------------------------------------------
  
  val compressorCharsIn = lz.compressorCharsIn
  val compressorCharsOut = 
  val characterBits = lz.characterBits
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(lz.characterBits != huffman.characterBits)
    // input cannot translate directly to both LZ and Huffman
    throw new IllegalArgumentException(s"LZ characterBits " +
      "(${lz.characterBits}) is not same as Huffman characterBits " +
      "(${huffman.characterBits}).")
  
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
    lz: edu.vt.cs.hardware_compressor.lz77.Parameters,
    huffman: huffmanParameters.huffmanParameters,): Parameters =
    new Parameters(
      lzParam = lz,
      huffmanParam = huffman)
  
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
    
    val lz77ParametersOutput = new Parameters(
      lzParam = edu.vt.cs.hardware_compressor
        .lz77.Parameters.fromCSV(map("lz")),
      huffmanParam = huffmanParameters.getHuffmanFromCSV
        .getHuffmanFromCSV(map("huffman")))
      
    System.err.println("Getting from CSV was successful")
    return lz77ParametersOutput
  }
}
