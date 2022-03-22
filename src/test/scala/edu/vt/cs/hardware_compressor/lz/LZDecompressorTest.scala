package edu.vt.cs.hardware_compressor.lz

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.lz.test._
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

class LZDecompressorTest extends AnyFlatSpec with Matchers {
  
  "LZ decompressor" should "passthrough uncompressed data" in {
    val params = Parameters.fromCSV("configFiles/lz.csv")
    val expect = Seq.fill(1000){(scala.math.random() * 256).toInt}
    val input =
      expect.flatMap(d => if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    
    chisel3.iotesters.Driver(() => new LZDecompressor(params)){lz =>
      new StreamComparisonTester(lz, input, expect)
    } should be (true)
  }
  
  "LZ decompressor" should "decompress compressed data (no overlap)" in {
    val params = Parameters.fromCSV("configFiles/lz.csv")
    var (expect, input) = LZGolden.generateData(10000, params, false)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new LZDecompressor(params))
    {lz =>
      new StreamComparisonTester(lz, input, expect)
    } should be (true)
  }
  
  "LZ decompressor" should "decompress compressed data (yes overlap)" in {
    val params = Parameters.fromCSV("configFiles/lz.csv")
    var (expect, input) = LZGolden.generateData(10000, params, true)
    
    chisel3.iotesters.Driver.execute(Array(),
      () => new LZDecompressor(params))
    {lz =>
      new StreamComparisonTester(lz, input, expect)
    } should be (true)
  }
}
