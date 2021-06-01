// package decompressorInput

// import chisel3._
// import chisel3.util._
// import inputsAndOutputs._
// import huffmanParameters._

// class decompressorInput(params: huffmanParameters = new huffmanParameters) extends Module {
//   val dataInCharacters = if (params.decompressorParallelInput) {
//     params.totalCompressorOutputCharacters
//   } else {
//     params.characterBufferSize
//   }

//   val io = IO(new Bundle {
//     val start = Input(Bool())
//     val input = new decompressorInputData(
//       params.characterBits,
//       dataInCharacters,
//       params.decompressorParallelInput,
//       params.decompressorCharacterBufferSize
//     )
//     val currentByteIn = Input(
//       UInt(log2Ceil(params.characters).W)
//     )
//     val dataOut =
//       Decoupled(
//         Vec(
//           params.decompressorCharacterBufferSize,
//           UInt(params.characterBits.W)
//         )
//       )
//   })

//   io.input.currentByteOut := io.currentByteIn

//   if (params.decompressorParallelInput) {
//     io.dataOut.valid := true.B
//     io.input.ready := true.B
//     // This is the case where the decompressor gets parallel input
//     if (params.decompressorInputRegister) {
//       // This is the case where the decompressor stores its inputs in a large bank of registers.
//       val inputData = Reg(Vec(dataInCharacters, UInt(params.characterBits.W)))
//       when(io.start) {
//         inputData := io.input.dataInArray
//       }

//       for (index <- 0 until params.decompressorCharacterBufferSize) {
//         io.dataOut.bits(index) := inputData(io.currentByteIn + index.U)
//       }
//     } else {
//       // This is the case where the decompressor has no input registers, only reads from a large bank of wires.
//       for (index <- 0 until params.decompressorCharacterBufferSize) {
//         io.dataOut.bits(index) := io.input.dataInArray(
//           io.currentByteIn + index.U
//         )
//       }
//     }
//   } else {
//     io.dataOut.valid := io.input.valid
//     io.input.ready := io.dataOut.ready
//     // This is the case where the decompressor gives serial output.
//     io.dataOut.bits := io.input.dataIn
//   }

// }

// object decompressorInput extends App {
//   chisel3.Driver.execute(Array[String](), () => new decompressorInput)
// }
