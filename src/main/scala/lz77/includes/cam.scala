package cam

import chisel3._
import chisel3.util._
import lz77Parameters._

class cam(params: lz77Parameters) extends Module {
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    val writeData = Flipped(Decoupled(UInt(params.characterBits.W)))
    // This input allows for search values to be requested from the CAM.
    val searchData = Flipped(Decoupled(UInt(params.characterBits.W)))

    // This output is the vector of whether each byte in the cam is a match or not.
    val matches = Output(Vec(params.camCharacters, Bool()))
  })

  io.matches := DontCare

  // This stores the byte history of the CAM. It starts initialized with zeroes, but that's alright as long as the compressor and decompressor both use the same CAM, I think.
  val byteHistory = RegInit(VecInit(Seq.fill(params.camCharacters)(0.U(params.characterBits.W))))

  // This handles the write data logic.
  io.writeData.ready := true.B
  when(io.writeData.valid){
    for(iteration <- 0 until (params.camCharacters-1)){
      byteHistory(iteration) := byteHistory(iteration+1)
    }
    byteHistory(params.camCharacters-1) := io.writeData.bits
  }

  // This handles the search data logic.
  io.searchData.ready := true.B
  when(io.searchData.valid){
    io.matches := byteHistory.map(_ === io.searchData.bits)
  }
}

object cam extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new cam(lz77Config))
}
