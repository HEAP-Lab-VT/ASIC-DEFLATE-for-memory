package lz77Compressor

import chisel3._
import chisel3.util._
import lz77Parameters._
import multiByteCAM._

class lz77Compressor(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(DecoupledStream(params.compressorMaxCharacters,
      UInt(params.characterBits.W)))
    val out = DecoupledStream(params.compressorMaxCharactersOut,
      UInt(params.characterBits.W)))
  })
  
  val cam = Module(new multiByteCAM(params))
  val encoder = Module(new LZ77Encoder(params))
  
  val camLitCount = Mux(cam.io.finished, 0.U, cam.io.literalCount)
  
  when(encoder.io.out.finished) {
    // if encoder is not working, connect CAM
    cam.io.charsIn <> io.in
  } otherwise {
    // if encoder is working, disconnect CAM
    io.in.ready := 0.U
    cam.io.charsIn.valid := 0.U
    cam.io.charsIn.bits := DontCare
    cam.io.charsIn.finished := io.in.finished
  }
  
  // limit literal count based on io.out.ready
  for(index <- 0 to params.camMaxCharsIn)
    when(index.U +
        PopCount(
          cam.io.charsIn.bits
          .take(index)
          .map(_ === params.escapeCharacter)
        ) <= io.out.ready
        && index.U <= cam.io.charsIn.valid) {
      cam.io.maxLiteralCount := index.U
    }
  
  // assert ready signal to encoder
  encoder.io.out.ready := Mux(io.out.ready > camLitCount, io.out.ready - camLitCount, 0.U)
  
  // connect CAM to encoder
  when(!cam.io.finished) {
    encoder.io.matchLength := cam.io.matchLength
    encoder.io.matchCAMAddress := cam.io.matchCAMAddress
  } otherwise {
    encoder.io.matchLength := 0.U
    encoder.io.matchCAMAddress := DontCare
  }
  
  // output characters from CAM and encoder
  io.out.bits := DontCare
  for(index <- 0 until params.compressorMaxCharactersOut)
    when(index.U < camLitCount) {
      io.out.bits(index) := cam.io.charsIn.bits(index)
    } elsewhen(index.U - camLitCount < encoder.out.valid && !encoder.out.finished) {
      io.out.bits(index) := encoder.out.bits(index.U - camLitCount)
    }
  
  // calculate valid and finished
  io.out.valid := (camLitCount +& encoder.out.valid) min params.compressorMaxCharactersOut.U
  io.out.finished := cam.io.finished && encoder.out.finished
}

object lz77Compressor extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  if (!lz77Config.camHistoryAvailable) {
    println(
      "Error, cam history must be available for lz77Compressor to work properly"
    )
    sys.exit(1)
  }
  chisel3.Driver
    .execute(Array[String](), () => new lz77Compressor(lz77Config))
}
