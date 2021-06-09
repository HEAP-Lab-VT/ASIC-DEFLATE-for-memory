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
  poke(lz77.io.in.finished, false)
  
  var iteration = 0
  while(peek(lz77.io.out.finished) == 0 && iteration < 10000) {
    if(inidx < data.length) {
      poke(lz77.io.in.bits(0), data(inidx))
      poke(lz77.io.in.valid, 1)
      poke(lz77.io.out.ready, 1)
    }
    else {
      poke(lz77.io.in.finished, true)
    }
    if(peek(lz77.io.out.valid) > 0) {
      println(peek(lz77.io.out.bits(0)).toString)
      expect(lz77.io.out.bits(0), data(outidx))
      outidx = outidx + 1
    }
    if(peek(lz77.io.in.ready) > 0) {
      inidx = inidx + 1
    }
    
    step(1)
    iteration = iteration + 1
  }
  if(outidx != data.length)
    fail
}


class LZ77DecompressorTest extends AnyFlatSpec with Matchers {
  "LZ77DecompressorTestUncompressable" should "pass" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    chisel3.iotesters.Driver(() => new lz77Decompressor(params))
      {lz77 => new LZ77DecompressorTestUncompressable(lz77, params,
        Seq(0, 1, 2, 3, 4, 5, 6, 7))} should be (true)
  }
}
