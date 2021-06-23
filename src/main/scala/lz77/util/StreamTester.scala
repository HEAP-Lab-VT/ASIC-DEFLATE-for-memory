package lz77.util

import lz77Decompressor._
import lz77Parameters._
import chisel3._
import chisel3.util._
import chisel3.iotesters._
import java.io._
import scala.util.control.Breaks._

class StreamTester[T <: Module {def io : StreamBundle[UInt, UInt]}](module: T, in: InputStream, out: OutputStream)
    extends PeekPokeTester(module) {
  
  val inBuf = new Array[Byte](module.io.in.bits.length)
  var inBufLen = 0
  val outBuf = new Array[Byte](module.io.out.bits.length)
  var outBufLen = 0
  
  breakable{ while(true) {
    // read input
    val bytesRead = in.read(inBuf, inBufLen, inBuf.length - inBufLen)
    
    // poke module inputs
    if(bytesRead == -1)
      poke(module.io.in.finished, true)
    else {
      poke(module.io.in.finished, false)
      inBufLen += bytesRead
    }
    for(i <- 0 until inBufLen)
      poke(module.io.in.bits(i), inBuf(i))
    poke(module.io.in.valid, inBufLen)
    
    poke(module.io.out.ready, outBuf.length - outBufLen)
    
    
    // peek module outputs
    if(peek(module.io.out.finished) == 1) break
    
    val inCount = (peek(module.io.in.valid) min peek(module.io.in.ready)).intValue
    inBufLen -= inCount
    for(i <- 0 until inBufLen)
      inBuf(i) = inBuf(i + inCount)
    
    val outCount = (peek(module.io.out.valid) min peek(module.io.out.ready)).intValue
    for(i <- 0 until outCount)
      outBuf(i + outBufLen) = peek(module.io.out.bits(i)).byteValue
    outBufLen += outCount
    
    step(1)
    
    // write output
    out.write(outBuf, 0, outBufLen)
    outBufLen = 0
  }}
}



object LZ77DecompressStdIO extends App {
  val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
  
  chisel3.iotesters.Driver.execute(args, () => new lz77Decompressor(params))
    {lz77 => new StreamTester(lz77, java.lang.System.in, java.lang.System.out)}
}
