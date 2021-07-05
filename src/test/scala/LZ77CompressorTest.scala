package lz77

import lz77Compressor._
import lz77Parameters._
import chisel3._
import chisel3.util._
import chisel3.iotesters._
import org.scalatest._
import org.scalatest.flatspec._
import matchers.should._
import chisel3.tester._

class LZ77CompressorTest extends AnyFlatSpec with Matchers {
  "LZ77 compressor" should "match golden" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    val input = LZ77Golden.generateData(10000, params)._1
    val expect = LZ77Golden.compress(input, params)
    
    chisel3.iotesters.Driver(() => new lz77Compressor(params)){lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
  
  "LZ77 compressor" should "compress short sequence" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    val input = Seq(0x78, 0x78, 0x61, 0x62, 0x63, 0x64 ,0x65, 0x79, 0x79, 0x61, 0x62, 0x63, 0x64, 0x65, 0x7a, 0x7a, 0x0a)
    val expect = Seq(0x78, 0x78, 0x61, 0x62, 0x63, 0x64 ,0x65, 0x79, 0x79, 0x67, 0xff, 0xca, 0x7a, 0x7a, 0x0a)
    
    chisel3.iotesters.Driver(() => new lz77Compressor(params)){lz77 =>
      new StreamComparisonTester(lz77, input, expect)
    } should be (true)
  }
}
