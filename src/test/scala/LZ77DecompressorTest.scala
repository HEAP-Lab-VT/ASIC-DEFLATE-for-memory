package lz77

import lz77Decompressor._
import lz77Parameters._
import chisel3._
import chisel3.util._
import chisel3.iotesters._
import org.scalatest._
import org.scalatest.flatspec._
import matchers.should._
import chisel3.tester._
import chisel3.experimental.BundleLiterals._
import chisel3.stage.ChiselStage
import java.io._

class LZ77DecompressorTestUncompressable(
    lz77: lz77Decompressor,
    params: lz77Parameters,
    data: Seq[Int])
    extends PeekPokeTester[lz77Decompressor](lz77) {
  
  // initialize inputs
  for(index <- 0 until lz77.io.in.bits.length)
    poke(lz77.io.in.bits(index), 0)
  poke(lz77.io.in.valid, 0)
  poke(lz77.io.out.ready, 0)
  poke(lz77.io.in.finished, false)
  
  var inidx = 0
  var outidx = 0
  
  var timeout = 10000
  while(peek(lz77.io.out.finished) == 0 && timeout > 0) {
    poke(lz77.io.in.valid, 0)
    poke(lz77.io.out.ready, 0)
    poke(lz77.io.in.finished, true)
    for(i <- 0 until (lz77.io.in.bits.length min (data.length - inidx))) {
      poke(lz77.io.in.bits(i), data(inidx + i))
      poke(lz77.io.in.valid, i + 1)
      poke(lz77.io.in.finished, false)
    }
    
    poke(lz77.io.out.ready, lz77.io.out.bits.length)
    
    inidx += (peek(lz77.io.in.ready) min (peek(lz77.io.in.valid))).intValue
    
    for(i <- 0 until (peek(lz77.io.out.ready) min (peek(lz77.io.out.valid))).intValue) {
      if(outidx + i < data.length)
        expect(lz77.io.out.bits(i), data(outidx + i))
      else
        fail
    }
    
    outidx += (peek(lz77.io.out.ready) min (peek(lz77.io.out.valid))).intValue
    
    step(1)
    timeout -= 1
  }
  
  if(outidx != data.length) {
    println(s"outidx was ${outidx}; Expected ${data.length}")
    fail
  }
}


class LZ77DecompressorTest extends AnyFlatSpec with Matchers {
  "LZ77DecompressorTestUncompressable" should "pass" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    chisel3.iotesters.Driver(() => new lz77Decompressor(params))
      {lz77 => new LZ77DecompressorTestUncompressable(lz77, params,
        Seq.fill(1000)(23))} should be (true)
  }
}
