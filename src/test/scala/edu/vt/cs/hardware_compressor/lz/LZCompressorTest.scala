package edu.vt.cs.hardware_compressor.lz

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.lz.test._
import chisel3._
import chisel3.util._
import chisel3.iotesters._
import org.scalatest._
import org.scalatest.flatspec._
import matchers.should._
import chisel3.tester._

class LZCompressorTest extends AnyFlatSpec with Matchers {
  "LZ compressor" should "match golden" in {
    val params = Parameters.fromCSV("configFiles/lz.csv")
    val input = LZGolden.generateData(10000, params)._1
    val expect = LZGolden.compress(input, params)
    
    chisel3.iotesters.Driver(() => new LZCompressor(params)){lz =>
      new StreamComparisonTester(lz, input, expect)
    } should be (true)
  }
  
  "LZ compressor" should "compress short sequence" in {
    val params = Parameters.fromCSV("configFiles/lz.csv")
    val input = Seq(0x78, 0x78, 0x61, 0x62, 0x63, 0x64 ,0x65, 0x79, 0x79, 0x61, 0x62, 0x63, 0x64, 0x65, 0x7a, 0x7a, 0x0a)
    val expect = Seq(0x78, 0x78, 0x61, 0x62, 0x63, 0x64 ,0x65, 0x79, 0x79, 0x67, 0xff, 0xca, 0x7a, 0x7a, 0x0a)
    
    chisel3.iotesters.Driver(() => new LZCompressor(params)){lz =>
      new StreamComparisonTester(lz, input, expect)
    } should be (true)
  }
}
