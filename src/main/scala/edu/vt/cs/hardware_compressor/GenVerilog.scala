package edu.vt.cs.hardware_compressor

import chisel3._
import edu.vt.cs.hardware_compressor._
import java.nio.file.Path

object GenVerilog extends App {
  private object options {
    var modName: Option[String] = None
    var hdlType: Option[String] = None
    var configFile: Option[String] = None
    var chiselArgs: Iterable[String] = Seq.empty
    var printConfig: Boolean = false
  }
  
  private var argsList: List[String] = args.toList
  while(argsList.nonEmpty) {
    argsList match {
    case "--module" :: modName :: rem =>
      options.modName = Some(modName)
      argsList = rem
    case "--config" :: config :: rem =>
      options.configFile = Some(config)
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
  
  private val stage = new chisel3.stage.ChiselStage()
  private def emitter(gen: => RawModule, args: Array[String]) =
    stage.emitVerilog(gen, args)
  options.modName.get match {
    case "LZCompressor" =>
      val params = lz.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new lz.LZCompressor(params),
        options.chiselArgs.toArray)
    case "LZDecompressor" =>
      val params = lz.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new lz.LZCompressor(params),
        options.chiselArgs.toArray)
    case "HuffmanCompressor" =>
      val params = huffman.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new huffman.HuffmanCompressor(params),
        options.chiselArgs.toArray)
    case "HuffmanDecompressor" =>
      val params = huffman.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new huffman.HuffmanDecompressor(params),
        options.chiselArgs.toArray)
    case "DeflateCompressor" =>
      val params = deflate.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new deflate.DeflateCompressor(params),
        options.chiselArgs.toArray)
    case "DeflateDecompressor" =>
      val params = deflate.Parameters.fromCSV(Path.of(options.configFile.get))
      if(options.printConfig) params.print()
      emitter(new deflate.DeflateDecompressor(params),
        options.chiselArgs.toArray)
    case a => throw new IllegalArgumentException(a)
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
