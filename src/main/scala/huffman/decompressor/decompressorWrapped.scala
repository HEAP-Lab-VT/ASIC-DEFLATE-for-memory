// package decompressorWrapped

// import chisel3._
// import chisel3.util._
// import decompressorInput._
// import huffmanDecompressor._
// import decompressorOutput._
// import inputsAndOutputs._
// import huffmanParameters._

// class decompressorWrapped(params: huffmanParameters = new huffmanParameters) extends Module {
//   val pipelineRegisters: Int = 0
//   val dataInCharacters = if (params.decompressorParallelInput) {
//     params.totalCompressorOutputCharacters
//   } else {
//     params.decompressorCharacterBufferSize
//   }

//   val dataOutSize = if (params.decompressorParallelOutput) {
//     params.inputCharacterAddressBits
//   } else {
//     1
//   }

//   val io = IO(new Bundle {
//     val start = Input(Bool())
//     val input = new decompressorInputData(
//       params.characterBits,
//       params.totalCompressorOutputCharacters,
//       params.decompressorParallelInput,
//       params.decompressorCharacterBufferSize
//     )
//     val output =
//       new decompressorOutputData(
//         params.decompressorParallelOutput,
//         params.characters,
//         params.characterBits
//       )
//     val finished = Output(Bool())
//   })

//   val inputReg = Module(
//     new decompressorInput(params, true)
//   )
//   val decompressor = Module(new huffmanDecompressor(params))

//   val startPrevious = Reg(Bool())
//   startPrevious := io.start
//   val startPulse = Wire(Bool())
//   startPulse := (startPrevious === false.B) && (io.start === true.B)

//   inputReg.io.start := startPulse
//   decompressor.io.start := startPulse
//   inputReg.io.input <> io.input
//   inputReg.io.currentByteIn <> decompressor.io.currentByte
//   decompressor.io.dataIn <> inputReg.io.dataOut

//   if (pipelineRegisters == 0) {
//     io.finished <> decompressor.io.finished
//   } else {
//     val dataOutRegisters = if (params.decompressorParallelOutput) {
//       Reg(
//         Vec(pipelineRegisters, Vec(dataOutSize, UInt(params.characterBits.W)))
//       )
//     } else {
//       Reg(Vec(pipelineRegisters, UInt(params.characterBits.W)))
//     }
//     val finishedOutRegisters = Reg(Vec(pipelineRegisters, Bool()))
//     if (params.decompressorParallelOutput) {
//     } else {
//     }
//     finishedOutRegisters(0) := decompressor.io.finished

//     for (index <- 1 until pipelineRegisters) {
//       dataOutRegisters(index) := dataOutRegisters(index - 1)
//       finishedOutRegisters(index) := finishedOutRegisters(index - 1)
//     }

//     io.output.dataOutArray := dataOutRegisters(pipelineRegisters - 1)
//     io.finished := finishedOutRegisters(pipelineRegisters - 1)
//   }

// }

// object decompressorWrapped extends App {
//   chisel3.Driver.execute(Array[String](), () => new decompressorWrapped)
// }
