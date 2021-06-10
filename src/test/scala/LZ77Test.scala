package lz77

import lz77CompressorDecompressor._
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

class LZ77Test(
    lz77: lz77CompressorDecompressor,
    params: lz77Parameters,
    datastream: InputStream)
    extends PeekPokeTester[lz77CompressorDecompressor](lz77) {
  
  val dataarray = new Array[Byte](
    (params.charactersToCompress * params.characterBits + 7) / 8)
  val remainder =
    dataarray.length * 8 - params.charactersToCompress * params.characterBits
  var it = 0
  while(datastream.read(dataarray) != -1 && it < 1) {
    val dataint = BigInt(dataarray)
    poke(lz77.io.in.asUInt, dataint)
    var it2 = 0
    while(peek(lz77.io.finished) == 0 && it2 < 100) {
      step(1)
      println(s"${it}, ${it2}")
      it2 = it2 + 1
    }
    expect(lz77.io.out.asUInt, dataint)
    reset()
    it = it + 1
  }
}


class LZ77TestImagick extends AnyFlatSpec with Matchers {
  "LZ77TestImagick" should "pass" in {
    val params = new getLZ77FromCSV().getLZ77FromCSV("configFiles/lz77.csv")
    val datastream = new FileInputStream("dumps/parseddumpfiles/imagickparsed")
    chisel3.iotesters.Driver(
    // chisel3.iotesters.Driver.execute(Array("--generate-vcd-output", "on"),
      () => new lz77CompressorDecompressor(params))
      {lz77 => new LZ77Test(lz77, params, datastream)} should be (true)
  }
}
