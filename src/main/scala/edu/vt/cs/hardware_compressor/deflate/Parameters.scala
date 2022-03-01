package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    lzParam: edu.vt.cs.hardware_compressor.lz77.Parameters,
    huffmanParam: edu.vt.cs.hardware_compressor.huffman.Parameters
) {
  
  //============================================================================
  // SUB-MODULE PARAMETERS
  //----------------------------------------------------------------------------
  
  val lz = lzParam
  val huffman = huffmanParam
  
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  val plnCharBits = lz.characterBits
  val encCharBits = huffman.compressedCharBits
  val encChannels = huffman.channelCount
  val intCharBits = lz.characterBits
  val compressorCharsIn = lz.compressorCharsIn
  val compressorCharsOut = huffman.compressorCharsOut
  val compressorIntBufSize = huffman.huffman.characters
  val decompressorCharsIn = huffman.decompressorCharsIn
  val decompressorCharsOut = lz.decompressorCharsOut
  val decompressorIntBufferSize =
    lz.decompressorCharsIn max huffman.decompressorCharsOut
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(lz.characterBits != huffman.characterBits)
    // input cannot translate directly to both LZ and Huffman
    throw new IllegalArgumentException(s"LZ characterBits " +
      "(${lz.characterBits}) is not same as Huffman characterBits " +
      "(${huffman.characterBits}).")
}

object Parameters {
  
  def apply(
    lz: edu.vt.cs.hardware_compressor.lz77.Parameters,
    huffman: edu.vt.cs.hardware_compressor.huffman.Parameters
  ): Parameters =
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
      huffmanParam = edu.vt.cs.hardware_compressor
        .huffman.Parameters.fromCSV(map("huffman")))
      
    System.err.println("Getting from CSV was successful")
    return lz77ParametersOutput
  }
}
