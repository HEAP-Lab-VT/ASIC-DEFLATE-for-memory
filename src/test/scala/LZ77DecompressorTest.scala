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
import scala.util.control.Breaks._

class LZ77DecompressorTest extends AnyFlatSpec with Matchers {
  
  def generateCompressed(
      params: lz77Parameters,
      len: Int = 4096,
      overlap: Boolean = true
  ): Tuple2[Seq[Int], Seq[Int]] = {
    var expect = Seq.fill(0){(scala.math.random() * 256).toInt}
    var input = expect.flatMap(d =>
      if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    while(expect.length < len) breakable {
      if(scala.math.random() >= .1 ||
          expect.length + params.minCharactersToEncode >= len ||
          expect.length == 0) {
        val char = (scala.math.random() * 256).toInt
        expect :+= char
        input :+= char
        if(char == params.escapeCharacter)
          input :+= char
      }
      else {
        val index = params.camCharacters - 1 - (scala.math.random() *
          (expect.length min params.camCharacters)).toInt
        var length = params.minCharactersToEncode
        val p = scala.math.random() * .3 + .7
        while(scala.math.random() < p && length < len - expect.length)
          length += 1
        if(index + length > params.camCharacters && !overlap) break // continue
        
        var encoding : BigInt = 0
        encoding <<= params.characterBits
        encoding |= params.escapeCharacter
        encoding <<= 1
        encoding |= ~params.escapeCharacter >> (params.characterBits - 1) & 1
        encoding <<= params.camAddressBits
        encoding |= index
        
        encoding <<= params.minEncodingSequenceLengthBits
        if(length <= params.maxCharactersInMinEncoding) {
          encoding |= length - params.minCharactersToEncode
        }
        else {
          encoding |= (1 << params.minEncodingSequenceLengthBits) - 1
          var remaining = length - params.maxCharactersInMinEncoding - 1
          while(remaining >= 0) {
            encoding <<= params.characterBits
            encoding |= params.maxCharacterValue min remaining
            remaining -= params.extraCharacterLengthIncrease
          }
        }
        
        val encodinglength =
          if(length <= params.maxCharactersInMinEncoding)
            params.minEncodingWidth / params.characterBits
          else
            (params.minEncodingWidth / params.characterBits) + 1 +
              ((length - params.maxCharactersInMinEncoding) /
                params.maxCharacterValue)
        
        for(i <- 0 until encodinglength reverse) {
          input :+= (encoding >> (i * params.characterBits) &
            params.maxCharacterValue).toInt
        }
        expect ++= Iterator.continually(
          (Seq.fill(params.camCharacters)(0) ++ expect)
          .takeRight(params.camCharacters)
          .drop(index))
          .flatten
          .take(length)
      }
    }
    
    (input, expect)
  }
  
  "LZ77 decompressor" should "passthrough uncompressed data" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    val expect = Seq.fill(1000){(scala.math.random() * 256).toInt}
    val input =
      expect.flatMap(d => if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    
    chisel3.iotesters.Driver(() => new lz77Decompressor(params)){lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
  
  "LZ77 decompressor" should "decompress compressed data (no overlap)" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    var (input, expect) = generateCompressed(params, 10000, false)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new lz77Decompressor(params))
    {lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
  
  "LZ77 decompressor" should "decompress compressed data (yes overlap)" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    var (expect, input) = LZ77Golden.generateData(10000, params, true)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new lz77Decompressor(params))
    {lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
}
