package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import Parameters._
import chisel3._
import chisel3.util._
import chisel3.iotesters._
import org.scalatest._
import org.scalatest.flatspec._
import matchers.should._
import chisel3.tester._
import chisel3.experimental.BundleLiterals._
import chisel3.stage.ChiselStage
import scala.util.control.Breaks._

class LZ77DecompressorTest extends AnyFlatSpec with Matchers {
  
  def generateCompressed(
      params: Parameters,
      len: Int = 4096,
      overlap: Boolean = true
  ): Tuple2[Seq[Int], Seq[Int]] = {
    var expect = Seq.fill(0){(scala.math.random() * 256).toInt}
    var input = expect.flatMap(d =>
      if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    while(expect.length < len) breakable {
      if(scala.math.random() >= .1 ||
          expect.length + params.minCharsToEncode >= len ||
          expect.length == 0) {
        val char = (scala.math.random() * 256).toInt
        expect :+= char
        input :+= char
        if(char == params.escapeCharacter)
          input :+= char
      }
      else {
        val index = params.camSize - 1 - (scala.math.random() *
          (expect.length min params.camSize)).toInt
        var length = params.minCharsToEncode
        val p = scala.math.random() * .3 + .7
        while(scala.math.random() < p && length < len - expect.length)
          length += 1
        if(index + length > params.camSize && !overlap) break // continue
        
        var encoding : BigInt = 0
        encoding <<= params.characterBits
        encoding |= params.escapeCharacter
        encoding <<= 1
        encoding |= ~params.escapeCharacter >> (params.characterBits - 1) & 1
        encoding <<= params.camSize.idxBits
        encoding |= index
        
        encoding <<= params.minEncodingLengthBits
        if(length <= params.maxCharsInMinEncoding) {
          encoding |= length - params.minCharsToEncode
        }
        else {
          encoding |= (1 << params.minEncodingLengthBits) - 1
          var remaining = length - params.maxCharsInMinEncoding - 1
          while(remaining >= 0) {
            encoding <<= params.characterBits
            encoding |= params.characterBits.maxVal min remaining
            remaining -= params.extraCharacterLengthIncrease
          }
        }
        
        val encodinglength =
          if(length <= params.maxCharsInMinEncoding)
            params.minEncodingChars
          else
            params.minEncodingChars + 1 +
              ((length - params.maxCharsInMinEncoding) /
                params.characterBits.maxVal.intValue)
        
        for(i <- 0 until encodinglength reverse) {
          input :+= (encoding >> (i * params.characterBits) &
            params.characterBits.maxVal).toInt
        }
        expect ++= Iterator.continually(
          (Seq.fill(params.camSize)(0) ++ expect)
          .takeRight(params.camSize)
          .drop(index))
          .flatten
          .take(length)
      }
    }
    
    (input, expect)
  }
  
  "LZ77 decompressor" should "passthrough uncompressed data" in {
    val params = Parameters.fromCSV("configFiles/lz77.csv")
    val expect = Seq.fill(1000){(scala.math.random() * 256).toInt}
    val input =
      expect.flatMap(d => if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    
    chisel3.iotesters.Driver(() => new LZ77Decompressor(params)){lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
  
  "LZ77 decompressor" should "decompress compressed data (no overlap)" in {
    val params = Parameters.fromCSV("configFiles/lz77.csv")
    var (input, expect) = generateCompressed(params, 10000, false)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new LZ77Decompressor(params))
    {lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
  
  "LZ77 decompressor" should "decompress compressed data (yes overlap)" in {
    val params = Parameters.fromCSV("configFiles/lz77.csv")
    var (expect, input) = LZ77Golden.generateData(10000, params, true)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new LZ77Decompressor(params))
    {lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
}
