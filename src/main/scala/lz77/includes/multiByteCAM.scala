package multiByteCAM

import chisel3._
import chisel3.util._
import lz77Parameters._

class multiByteCAM(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    val writeData = Flipped(Valid(UInt(params.characterBits.W)))
    // This input allows for search values to be requested from the CAM.
    val searchData =
      Input(Vec(params.camMaxPatternLength, UInt(params.characterBits.W)))

    // This output is the vector of whether each byte in the cam is a match or not.
    val matches =
      Output(Vec(params.camMaxPatternLength, Vec(params.camCharacters, Bool())))

    // This output is only used when the multiByteCAM is being used by the lz77Compressor.
    val camHistory =
      if (params.camHistoryAvailable)
        Some(Output(Vec(params.camCharacters, UInt(params.characterBits.W))))
      else None
  })

  // This stores the byte history of the CAM. It starts initialized with zeroes, but that's alright as long as the compressor and decompressor both use the same CAM, I think.
  val byteHistory = RegInit(
    VecInit(Seq.fill(params.camCharacters)(0.U(params.characterBits.W)))
  )
  if (params.camHistoryAvailable) {
    io.camHistory.get := byteHistory
  }
  // This stores the number of bytes currently stored in the CAM.
  val camBytes = RegInit(UInt(params.camCharacterCountBits.W), 0.U)

  // This handles the write data logic.
  when(camBytes < params.camCharacters.U) {
    when(io.writeData.valid) {
      byteHistory(camBytes) := io.writeData.bits
      camBytes := camBytes + 1.U
    }
  }

  // This handles the search data logic.
  for (index <- 0 until params.camMaxPatternLength) {
    io.matches(index) := byteHistory.map(_ === io.searchData(index))
  }
}

object multiByteCAM extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new multiByteCAM(lz77Config))
}
