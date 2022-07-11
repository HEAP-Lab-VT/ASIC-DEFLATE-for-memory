package edu.vt.cs.hardware_compressor.deflate

import chisel3._
import chisel3.util._
import edu.vt.cs._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.PrintWriter
import java.nio.file.Path
import scala.collection.{mutable, Map, SortedMap}
import scala.util.Using

class Parameters(
    lzParam: hardware_compressor.lz.Parameters,
    huffmanParam: hardware_compressor.huffman.Parameters
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
  // PRINTING
  //----------------------------------------------------------------------------
  
  lazy val map: Map[String, Any] = (SortedMap(
    "characterBits" -> characterBits,
    "compressorCharsIn" -> compressorCharsIn,
    "compressorBitsOut" -> compressorBitsOut,
    "decompressorBitsIn" -> decompressorBitsIn,
    "decompressorCharsOut" -> decompressorCharsOut,
    "decompressorMidBufferSize" -> decompressorMidBufferSize
  )
    ++ lz.map.map{case (k, v) => ("lz." + k, v)}
    ++ huffman.map.map{case (k, v) => ("huffman." + k, v)})
  
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
        .replaceAll("[ .]", "_")
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
    lz: hardware_compressor.lz.Parameters,
    huffman: hardware_compressor.huffman.Parameters
  ): Parameters =
    new Parameters(
      lzParam = lz,
      huffmanParam = huffman)
  
  def fromCSV(csvPath: Path): Parameters = {
    var map: mutable.Map[String, String] = mutable.Map.empty
    Using(io.Source.fromFile(csvPath.toFile)){lines =>
      for(line <- lines.getLines()) {
        val cols = line.split(",").map(_.trim)
        if(cols.length == 2) {
          map += (cols(0) -> cols(1))
        } else if(cols.length != 0) {
          System.err.println("Warning: " +
            "Each line must have exactly two values " +
            "separated by a comma.\n" +
            s"The line\n$line\ndoes not meet this requirement.")
        }
      }
    }
    
    val lzParametersOutput = new Parameters(
      lzParam = edu.vt.cs.hardware_compressor
        .lz.Parameters.fromCSV(Path.of(map("lz"))),
      huffmanParam = edu.vt.cs.hardware_compressor
        .huffman.Parameters.fromCSV(Path.of(map("huffman")))
    )
    return lzParametersOutput
  }
}
