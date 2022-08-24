package edu.vt.cs.hardware_compressor

import chisel3._
import edu.vt.cs.hardware_compressor._
import java.io.PrintWriter
import java.nio.file.Path
import scala.util.Using

object GenVerilog extends App {
  private object options {
    var compression: Option[String] = None
    var hdlType: Option[String] = None
    var genCompressor: Boolean = false
    var genDecompressor: Boolean = false
    var genCppConfig: Option[String] = None
    var cppConfigPrefix: String = ""
    var configFile: Option[String] = None
    var chiselArgs: Iterable[String] = Seq.empty
    var printConfig: Boolean = false
  }
  
  private var argsList: List[String] = args.toList
  while(argsList.nonEmpty) {
    argsList match {
    case "--compression" :: compression :: rem =>
      options.compression = Some(compression)
      argsList = rem
    case "--config" :: config :: rem =>
      options.configFile = Some(config)
      argsList = rem
    case "--gen-compressor" :: rem =>
      options.genCompressor = true
      argsList = rem
    case "--gen-decompressor" :: rem =>
      options.genDecompressor = true
      argsList = rem
    case "--gen-cpp-config" :: file :: rem =>
      options.genCppConfig = Some(file)
      argsList = rem
    case "--cpp-config-prefix" :: prefix :: rem =>
      options.cppConfigPrefix = prefix
      argsList = rem
    case "--print-config" :: rem =>
      options.printConfig = true
      argsList = rem
    case "--no-print-config" :: rem =>
      options.printConfig = false
      argsList = rem
    case "--" :: rem =>
      options.chiselArgs = options.chiselArgs ++ rem
      argsList = Nil
    case rem =>
      options.chiselArgs = options.chiselArgs ++ rem
      argsList = Nil
    }
  }
  
  if(options.compression.isDefined) {
    val stage = new chisel3.stage.ChiselStage()
    def emitter(gen: => RawModule, args: Iterable[String]) =
      stage.emitVerilog(gen, args.toArray)
    val configPath = Path.of(options.configFile.get)
    options.compression.get match {
    case "LZ" =>
      val params = lz.Parameters.fromCSV(configPath)
      if(options.printConfig) params.print()
      options.genCppConfig.foreach(f => Using(new PrintWriter(f))(w =>
        params.genCppDefines(w, options.cppConfigPrefix)))
      if(options.genCompressor)
        emitter(new lz.LZCompressor(params), options.chiselArgs)
      if(options.genDecompressor)
        emitter(new lz.LZDecompressor(params), options.chiselArgs)
    case "Huffman" =>
      val params = huffman.Parameters.fromCSV(configPath)
      if(options.printConfig) params.print()
      options.genCppConfig.foreach(f => Using(new PrintWriter(f))(w =>
        params.genCppDefines(w, options.cppConfigPrefix)))
      if(options.genCompressor)
        emitter(new huffman.HuffmanCompressor(params), options.chiselArgs)
      if(options.genDecompressor)
        emitter(new huffman.HuffmanDecompressor(params), options.chiselArgs)
    case "Deflate" =>
      val params = deflate.Parameters.fromCSV(configPath)
      if(options.printConfig) params.print()
      options.genCppConfig.foreach(f => Using(new PrintWriter(f))(w =>
        params.genCppDefines(w, options.cppConfigPrefix)))
      if(options.genCompressor)
        emitter(new deflate.DeflateCompressor(params), options.chiselArgs)
      if(options.genDecompressor)
        emitter(new deflate.DeflateDecompressor(params), options.chiselArgs)
    case a => throw new IllegalArgumentException(a)
    }
  }
  
  private def argSplit(args: String): Array[String] = {
    var splitArgs = collection.mutable.ArrayBuffer.empty[String]
    var curArg = new collection.mutable.StringBuilder()
    var nonEmpty = false
    var quote: Option[Char] = None
    var i = 0
    while(i < args.length) {
      args(i) match {
      case '\\' =>
        i += 1
        if(args.length <= i)
          throw new IllegalArgumentException("invalid escape sequence")
        curArg += args(i)
      case '\"' | '\'' if quote.isEmpty =>
        quote = Some(args(i))
        nonEmpty = true
      case c if quote.exists(c == _) =>
        quote = None
      case c if c.isWhitespace && quote.isEmpty =>
        if(curArg.nonEmpty || nonEmpty) {
          splitArgs += curArg.result()
          curArg.clear()
          nonEmpty = false
        }
      case c =>
        curArg += c
      }
      
      i += 1
    }
    
    if(quote.nonEmpty)
      throw new IllegalArgumentException("unclosed quote")
    
    splitArgs.toArray
  }
}
