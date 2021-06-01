package inputsAndOutputs

import chisel3._
import chisel3.util._
import huffmanParameters._

class characterFrequencyModuleOutputs(
    treeCharacters: Int = 32,
    sortModuleMuxBits: Int = 13,
    characterBits: Int = 8
) extends Bundle {
  val sortedFrequency = Output(Vec(treeCharacters, UInt(sortModuleMuxBits.W)))
  val sortedCharacter =
    Output(Vec(treeCharacters, UInt((characterBits + 1).W)))
  override def cloneType =
    (new characterFrequencyModuleOutputs(
      treeCharacters,
      sortModuleMuxBits,
      characterBits
    )).asInstanceOf[this.type]
}

class codewordGeneratorOutputs( val params: huffmanParameters) extends Bundle {
  val codewords = Output(
    Vec(params.characterPossibilities, UInt(params.codewordPlusCharacterBits.W))
  )
  val lengths = Output(
    Vec(params.characterPossibilities, UInt(params.escapeCharacterLengthMaxBits.W))
  )
  val charactersOut = Output(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  ) // This is not just the
  // characters in order from 0 to 255. Although the codewords are written
  // that way, these are used to communicate to the compressorOutput module
  // the order in which the codewords were generated so that the
  // decompressor reads the tree back correctly.
  val nodes = Output(UInt(params.characterCountBits.W))
  val escapeCharacters = Output(Vec(params.characterPossibilities, Bool()))
  val escapeCharacterLength = Output(UInt(params.codewordLengthMaxBits.W))
  val depthCounts = Output(Vec(16, UInt(8.W)))
  val treeMaxDepth = Output(UInt(4.W))
  val escapeCodeword = Output(UInt(16.W))
  override def cloneType =
    (new codewordGeneratorOutputs(params)).asInstanceOf[this.type]
}

class compressorInputData(
    val params: huffmanParameters,
    val isCharacterFrequencyInput: Boolean
) extends Bundle {

  val dataInCharacters = if (params.compressorParallelInput) {
    params.characters
  } else {
    if (isCharacterFrequencyInput) {
      params.characterFrequencyParallelism
    } else {
      1
    }
  }
  // This is the address of the first byte that is being requested byt the compressorInput
  val currentByteOut = Output(
    UInt(
      if (params.compressorParallelInput) 0.W
      else params.inputCharacterAddressBits.W
    )
  )
  val dataIn = Input(Vec(dataInCharacters, UInt(params.characterBits.W)))
  // This tells if the data in is valid or not.
  val valid = Input(Bool())
  // This tells if the compressor is ready to receive the data and move on to the next piece of data.
  val ready = Output(Bool())
  // This creates the optional port to tell how many characters to stop compressing at.
  val compressionLimit =
    if (params.variableCompression)
      Some(Input(UInt(params.inputCharacterBits.W)))
    else None
}

class compressorOutputData(val params: huffmanParameters) extends Bundle {
  // This will be an output if there is an output register. If not, then this will be optimized out of the Verilog because it has a width of 0.
  val dataOutArray = Output(
    Vec(
      if (params.compressorParallelOutput)
        params.totalCompressorOutputCharacters
      else 0,
      UInt(if (params.compressorParallelOutput) params.characterBits.W else 0.W)
    )
  )
  // These outputs will be used if there is no output register. Otherwise, it will be optimized out with a width of 0.
  val dataOut = Output(
    UInt(
      if (params.compressorParallelOutput) 0.W
      else params.dictionaryEntryMaxBits.W
    )
  )
  val dataLength = Output(
    UInt(
      if (params.compressorParallelOutput) 0.W
      else params.compressorOutputLengthMaxBits.W
    )
  )
  val valid = Output(Bool())
  val ready = Input(Bool())
}

class decompressorInputData(val params: huffmanParameters) extends Bundle {
  val dataIn = Input(UInt(params.decompressorInputBits.W))
  val currentByte = Output(
    UInt(params.totalCompressorOutputCharactersAddressBits.W)
  )
}

class decompressorOutputData(
    outputRegister: Boolean = true,
    dataOutSize: Int = 4096,
    characterBits: Int = 8
) extends Bundle {
  val dataOutArray = Output(
    Vec(
      if (outputRegister) dataOutSize else 0,
      UInt(if (outputRegister) characterBits.W else 0.W)
    )
  )
  val dataOut = Output(UInt(if (outputRegister) 0.W else characterBits.W))
  // This tells if the data out is valid or not.
  val valid = Output(Bool())
  // This tells if the decompressor can finish sending the current data out and go to the next piece of data
  val ready = Input(Bool())
}

class huffmanStatistics(params: huffmanParameters) extends Bundle {
  val huffmanCharacterDepths = Output(
    Vec(params.codewordMaxBits + 1, UInt(params.characterCountBits.W))
  )
  val escapeCharacterLength = Output(UInt(params.codewordLengthMaxBits.W))
  val huffmanTreeCharactersUsed = Output(UInt(params.validCharacterBits.W))
}

class sortOutputs(
    val nodes: Int = 32,
    val dataBits: Int = 8,
    val tagBits: Int = 9
) extends Bundle {
  val outputData = Output(Vec(nodes, UInt(dataBits.W)))
  val outputTags = Output(Vec(nodes, UInt(tagBits.W)))
  val itemNumber = Output(UInt(log2Ceil(nodes + 1).W))
}

class treeDepthCounterOutputs(val params: huffmanParameters) extends Bundle {
  val characters = Output(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  )
  val depths = Output(
    Vec(params.huffmanTreeCharacters, UInt(params.maxPossibleTreeDepthBits.W))
  )
  val validCharacters = Output(UInt(params.validCharacterBits.W))
}

class treeDepthCounterWrapperOutputs(val params: huffmanParameters)
    extends Bundle {
  val characters = Output(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  )
  val depths = Output(
    Vec(params.huffmanTreeCharacters, UInt(params.maxPossibleTreeDepthBits.W))
  )
  val validCharacters = Output(UInt(params.validCharacterBits.W))
}

class treeGeneratorOutputs(val params: huffmanParameters) extends Bundle {
  val leftNode = Output(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val rightNode = Output(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val leftNodeIsCharacter = Output(Vec(params.treeNodes, Bool()))
  val rightNodeIsCharacter = Output(Vec(params.treeNodes, Bool()))
  val validNodes = Output(UInt(params.validTreeNodeBits.W))
  val validCharacters = Output(UInt(params.validCharacterBits.W))
}

class treeGeneratorWrapperOutputs(val params: huffmanParameters)
    extends Bundle {
  val leftNode = Output(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val rightNode = Output(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val leftNodeIsCharacter = Output(Vec(params.treeNodes, Bool()))
  val rightNodeIsCharacter = Output(Vec(params.treeNodes, Bool()))
  val validNodes = Output(UInt(params.validTreeNodeBits.W))
  val validCharacters = Output(UInt(params.validCharacterBits.W))
}

class treeNormalizerOutputs(
    validNodeBits: Int,
    treeCharacters: Int,
    characterInBits: Int,
    treeDepthBits: Int
) extends Bundle {
  val charactersOut = Output(Vec(treeCharacters, UInt(characterInBits.W)))
  val depthsOut = Output(Vec(treeCharacters, UInt(treeDepthBits.W)))
  val validNodesOut = Output(UInt(validNodeBits.W))
  override def cloneType =
    (new treeNormalizerOutputs(
      validNodeBits,
      treeCharacters,
      characterInBits,
      treeDepthBits
    )).asInstanceOf[this.type]
}
