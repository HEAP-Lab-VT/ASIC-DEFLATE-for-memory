package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor._
import edu.vt.cs.hardware_compressor.util.WidthOps._

class Parameters(
    lzParam: lz.Parameters,
    huffmanParam: huffman.Parameters
) {
  
  //============================================================================
  // SUB-MODULE PARAMETERS
  //----------------------------------------------------------------------------
  
  val lz = lzParam
  val huffman = huffmanParam
  
  
  //============================================================================
  // GENERAL PARAMETERS
  //----------------------------------------------------------------------------
  
  val characterBits = lz.characterBits
  val compressorCharsIn = lz.compressorCharsIn
  val compressorBitsOut = huffman.compressorBitsOut
  val decompressorBitsIn = huffman.decompressorBitsIn
  val decompressorCharsOut = lz.decompressorCharsOut
  val decompressorMidBufferSize =
    lz.decompressorCharsIn max huffman.decompressorCharsOut
  
  
  //============================================================================
  // ASSERTIONS
  //----------------------------------------------------------------------------
  
  if(lz.characterBits != huffman.characterBits)
    // input cannot translate directly to both LZ and Huffman
    throw new IllegalArgumentException(s"LZ characterBits " +
      "(${lz.characterBits}) is not same as Huffman characterBits " +
      "(${huffman.characterBits}).")
  
  
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
    define("COMPRESSOR_CHARS_IN", compressorCharsIn)
    define("COMPRESSOR_BITS_OUT", compressorBitsOut)
    define("DECOMPRESSOR_BITS_IN", decompressorBitsIn)
    define("DECOMPRESSOR_CHARS_OUT", decompressorCharsOut)
    define("DECOMPRESSOR_MID_BUFFER_SIZE", decompressorMidBufferSize)
    
    lz.generateCppDefines(sink, prefix + "LZ_", conditional)
    huffman.generateCppDefines(sink, prefix + "HUFFMAN_", conditional)
  }
}

object Parameters {
  
  def apply(
    lz: edu.vt.cs.hardware_compressor.lz.Parameters,
    huffman: edu.vt.cs.hardware_compressor.huffman.Parameters
  ): Parameters =
    new Parameters(
      lzParam = lz,
      huffmanParam = huffman)
  
  def fromCSV(csvPath: String): Parameters = {
    System.err.println(s"getting deflate parameters from $csvPath...")
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
    
    val lzParametersOutput = new Parameters(
      lzParam = edu.vt.cs.hardware_compressor
        .lz.Parameters.fromCSV(map("lz")),
      huffmanParam = edu.vt.cs.hardware_compressor
        .huffman.Parameters.fromCSV(map("huffman")))
      
    System.err.println(s"finished getting deflate parameters from $csvPath.")
    return lzParametersOutput
  }
}
