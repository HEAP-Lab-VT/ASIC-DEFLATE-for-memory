package sort

import chisel3._
import chisel3.util._
import inputsAndOutputs._
import huffmanParameters._

class sort(params: huffmanParameters) extends Module {
  val sortLeastToGreatest = true
  val sortMuxBits = log2Ceil(params.huffmanTreeCharacters) + 1
  val internalSortBits = params.maxPossibleTreeDepthBits + 1

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputs = Flipped(new treeDepthCounterOutputs(params))
    val outputs = new sortOutputs(params.huffmanTreeCharacters, params.treeDepthInputBits, params.characterRepresentationBits)
    val finished = Output(Bool())
  })

  val waiting :: sorting :: Nil = Enum(2)

  val state = RegInit(UInt(1.W), waiting)

  val iteration = Reg(UInt(sortMuxBits.W))
  val sortData = Reg(Vec(params.huffmanTreeCharacters, UInt(internalSortBits.W)))
  val tagData = Reg(Vec(params.huffmanTreeCharacters, UInt(params.characterRepresentationBits.W)))
  val itemNumber = Reg(UInt(sortMuxBits.W))
  val tempSortData = Reg(Vec(params.huffmanTreeCharacters, UInt(internalSortBits.W)))
  val tempTagData = Reg(Vec(params.huffmanTreeCharacters, UInt(params.characterRepresentationBits.W)))
  val sortedSortData = Reg(Vec(params.huffmanTreeCharacters, UInt(internalSortBits.W)))
  val sortedTagData = Reg(Vec(params.huffmanTreeCharacters, UInt(params.characterRepresentationBits.W)))

  switch(state) {
    is(waiting) {
      when(io.start) {
        sortData := io.inputs.depths
        tempSortData := Seq.fill(params.huffmanTreeCharacters)(
          (1 << (internalSortBits - 1)).U(internalSortBits.W)
        )
        sortedSortData := Seq.fill(params.huffmanTreeCharacters)(
          (1 << (internalSortBits - 1)).U(internalSortBits.W)
        )
        tagData := io.inputs.characters
        // Don't need to define tags here, it would just waste energy
        itemNumber := io.inputs.validCharacters
        iteration := 0.U
        state := sorting
      }
    }
    is(sorting) {
      iteration := iteration + 1.U
      when(iteration >= itemNumber * 2.U - 1.U) {
        state := waiting
      }
      when(iteration < itemNumber) {
        tempSortData(0) := sortData(iteration)
        tempTagData(0) := tagData(iteration)
      }.otherwise {
        tempSortData(0) := (1 << (internalSortBits - 1)).U(internalSortBits.W)
      }

      for (index <- 0 until params.huffmanTreeCharacters) {
        when(tempSortData(index) < sortedSortData(index)) {
          sortedSortData(index) := tempSortData(index)
          sortedTagData(index) := tempTagData(index)
          if (index + 1 < params.huffmanTreeCharacters) {
            tempSortData(index + 1) := sortedSortData(index)
            tempTagData(index + 1) := sortedTagData(index)
          }
        }.otherwise {
          if (index + 1 < params.huffmanTreeCharacters) {
            tempSortData(index + 1) := tempSortData(index)
            tempTagData(index + 1) := tempTagData(index)
          }
        }
      }
    }
  }
  if (sortLeastToGreatest) {
    io.outputs.outputData := sortedSortData
    io.outputs.outputTags := sortedTagData
  } else {
    for (index <- 0 until params.huffmanTreeCharacters) {
      io.outputs.outputData(index) := sortedSortData(params.huffmanTreeCharacters - 1 - index)
      io.outputs.outputTags(index) := sortedTagData(params.huffmanTreeCharacters - 1 - index)
    }
  }
  io.outputs.itemNumber := itemNumber
  io.finished := state === waiting
}

object sort extends App {
  // chisel3.Driver.execute(Array[String](), () => new sort)
}
