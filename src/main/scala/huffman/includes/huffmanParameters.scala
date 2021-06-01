package huffmanParameters

import chisel3._
import chisel3.util._
import scala.math.pow

class huffmanParameters(
    // charactersParam: Int = 4096,
    // characterBitsParam: Int = 8,
    // huffmanTreeCharactersParam: Int = 32,
    // codewordMaxBitsParam: Int = 15,
    // outputRegistersParam: Boolean = false,
    // inputRegisterParam: Boolean = false,
    // treeNormalizerInputRegisterParam: Boolean = false, // This probably isn't necessary anymore.
    // characterBufferSizeParam: Int = 1,
    // treeDesiredMaxDepthParam: Int = 15, // Should be the same as codewordMaxBitsParam, probably should be eliminated.
    // treeDepthInputBitsParam: Int = 8, // Should probably be eliminated or derived.
    // characterFrequencyParallelismParam: Int = 1,
    // compressionParallelismParam: Int = 1,
    // decompressorCharacterBufferSizeParam: Int = 4, // This should probably be derived.
    // // These parameters are used primarily for ease of working with testbenches.
    // compressorParallelInputParam: Boolean = false,
    // compressorParallelOutputParam: Boolean = false,
    // compressorInputRegisterParam: Boolean = false,
    // decompressorParallelInputParam: Boolean = false,
    // decompressorParallelOutputParam: Boolean = false,
    // decompressorInputRegisterParam: Boolean = false,
    // variableCompressionParam: Boolean = true,
    // debugStatisticsParam: Boolean = false,
    charactersParam: Int,
    characterBitsParam: Int,
    huffmanTreeCharactersParam: Int,
    codewordMaxBitsParam: Int,
    outputRegistersParam: Boolean,
    inputRegisterParam: Boolean,
    treeNormalizerInputRegisterParam: Boolean, // This probably isn't necessary anymore.
    characterBufferSizeParam: Int,
    treeDesiredMaxDepthParam: Int, // Should be the same as codewordMaxBitsParam, probably should be eliminated.
    treeDepthInputBitsParam: Int, // Should probably be eliminated or derived.
    characterFrequencyParallelismParam: Int,
    compressionParallelismParam: Int,
    decompressorCharacterBufferSizeParam: Int, // This should probably be derived.
    // These parameters are used primarily for ease of working with testbenches.
    compressorParallelInputParam: Boolean,
    compressorParallelOutputParam: Boolean,
    compressorInputRegisterParam: Boolean,
    decompressorParallelInputParam: Boolean,
    decompressorParallelOutputParam: Boolean,
    decompressorInputRegisterParam: Boolean,
    variableCompressionParam: Boolean,
    debugStatisticsParam: Boolean
) {

  // Direct parameters

  // This tells how many characters the compressor is set up to compress in one batch.
  var characters = charactersParam
  // This controls how many bits are used to represent an uncompressed character.
  var characterBits = characterBitsParam
  // This controls how many characters are used in the huffman tree dictionary before it
  // is truncated. If necessary, an escape character will be used to represent any
  // characters that do not fit in the dictionary.
  var huffmanTreeCharacters = huffmanTreeCharactersParam
  // This controls the maximum number of bits used to represent a character in compressed
  // form.
  var codewordMaxBits = codewordMaxBitsParam
  // This controls whether the compressor input is parallel or serial.
  var compressorParallelInput = compressorParallelInputParam
  // This controls whether the compressor output is parallel or serial.
  var compressorParallelOutput = compressorParallelOutputParam
  // This controls whether the compressor input is wires or registers. This only matters if the input is set as parallel.
  var compressorInputRegister = compressorInputRegisterParam
  // This controls whether the decompressor input is parallel or serial.
  var decompressorParallelInput = decompressorParallelInputParam
  // This controls whether the decompressor output is parallel or serial.
  var decompressorParallelOutput = decompressorParallelOutputParam
  // This controls whether the decompressor input is wires or registers. This only matters if the input is set as parallel.
  var decompressorInputRegister = decompressorInputRegisterParam
  // This controls whether the tree normalizer uses registers to capture its inputs as
  // soon as they become valid or takes its inputs as wires and assumes they will not
  // change. A varue of true means registers are used, false means wires are used.
  var treeNormalizerInputRegister = treeNormalizerInputRegisterParam
  // This controls the size of the input buffer for the input of the compressor.
  var characterBufferSize = characterBufferSizeParam
  // This controls what the desired max depth of the treeNormalizer is and the bit width
  // of hardware that is generated after the treeNormalizer.
  var treeDesiredMaxDepth = treeDesiredMaxDepthParam
  // This controls how many bits the depths for the treeNormalizer inputs are, which
  // affects the internal representation of this data for the treeNormalizer.
  var treeDepthInputBits = treeDepthInputBitsParam
  // This is the number of bytes that are counted at once by the characterFrequencyCounter. This number may be used to override the
  // characterBufferSize for the characterFrequencyCounter compressorInput module.
  var characterFrequencyParallelism = characterFrequencyParallelismParam
  // This is the number of independent compression output streams that will run at one time.
  var compressionParallelism = compressionParallelismParam
  // This is the number of characters in the character buffer for the decompressor.
  var decompressorCharacterBufferSize = decompressorCharacterBufferSizeParam
  // This controls whether or not the compressor and decompressor support not compressing all the characters in charactersParam, stopping early.
  var variableCompression = variableCompressionParam
  // This controls whether or not the debug statistics are generated.
  var debugStatistics = debugStatisticsParam

  // Derived parameters

  // This is the maximum number of bits to represent how long a given codeword is.
  var codewordLengthMaxBits = log2Ceil(codewordMaxBits + 1)
  // This is the maximum number of bits needed to represent the length of a codeword including the escape character.
  var escapeCharacterLengthMaxBits = log2Ceil(codewordMaxBits + characterBits + 1)
  // This decides) how many bits will be used on the output of the treeNormalizer for character depth.
  var treeDepthOutputBits = log2Ceil(treeDesiredMaxDepth + 1)
  // This is the number of nodes in the tree for the huffman encoding
  var treeNodes = 2 * huffmanTreeCharacters
  // This is the maximum possible depth for the huffman tree based on the number of allowed characters,
  var maxPossibleTreeDepth = huffmanTreeCharacters
  // This is the number of bits required to represent the maximum possible tree depth
  var maxPossibleTreeDepthBits = log2Ceil(maxPossibleTreeDepth + 1)
  // This is the number of bits required to represent how many of the tree nodes are valid as inputs to new modules.
  var validTreeNodeBits = log2Ceil(treeNodes + 1)
  // This is the number of bits required to represent how many of the characters in the huffman tree are valid.
  var validCharacterBits = log2Ceil(huffmanTreeCharacters + 1)
  // This is the number of bits needed to represent an uncompressed character plus an extra bit for
  // showing if the character is the escape character or not.
  var characterRepresentationBits = characterBits + 1
  // This is the number of bits required to represent the number of input characters to be compressed. This is different
  // from the inputCharacterAddressBits because, if you have a power of 2 number of characters total and have a single character
  // for the whole input data, you won't be able to express that frequency with inputCharacterAddressBits, but you will with the
  // extra bit afforded for this number of bits.
  var inputCharacterBits = log2Ceil(characters + 1)
  // This is the number of bits required to represent the position of any of the characters in the input.
  var inputCharacterAddressBits = log2Ceil(characters)
  // This is the number of bits required to represent a dictionary entry in the huffman tree.
  var dictionaryEntryMaxBits =
    characterRepresentationBits + codewordLengthMaxBits + codewordMaxBits
  // This is the number of bits required to express how many bits long a given output cycle of the compressor output is.
  var compressorOutputLengthMaxBits = log2Ceil(dictionaryEntryMaxBits + 1)
  // This is needed for totalCompressorOutputCharacters. It's a stupid Scala convention that True != 1 and False != 0, so I
  // have to convert the boolean to an integer myself.
  var modulusOverflow =
    if (((huffmanTreeCharacters * dictionaryEntryMaxBits) % characterBits) > 0)
      1
    else 0
  // This is the number of characters that the compressor output can be in total. The modulus at the end makes sure that,
  // if the number of bits is not divisable by the number of character bits, the extra character to represent those extra
  // bits exists.
  var totalCompressorOutputCharacters =
    characters + (huffmanTreeCharacters * dictionaryEntryMaxBits) / characterBits + modulusOverflow
  // This is the number of bits needed to express an address of one of the output bits of the total compressor output.
  var totalCompressorOutputCharactersAddressBits = log2Ceil(
    totalCompressorOutputCharacters
  )
  // This is the number of different possibilities that can be represented with the number of bits for a character.
  var characterPossibilities = 1 << characterBits
  // This is the number of bits of input for a single thread of the decompressor input.
  var decompressorInputBits = characterBits * decompressorCharacterBufferSize
  // This is the number of characters each parallel instance of the decompressor or compressor will handle.
  var parallelCharacters = characters / compressionParallelism
  // This is the number of bits needed to count up to the number of parallel characters inclusively.
  var parallelCharactersNumberBits = log2Ceil(parallelCharacters + 1)
  // This is the number of bits needed to address any of the bits in the parallel characters.
  var parallelCharactersBitAddressBits = log2Ceil(
    parallelCharacters * characterBits
  )
  // This is the number of bits in a codeword, including the extra bits if the codeword is an escape character to include the actual character itself.
  var codewordPlusCharacterBits = codewordMaxBits + characterBits
  // This is the number of bits needed to count through all the characters and not overflow when going one above.
  var characterCountBits = characterBits + 1

  // Function for checking for invalid configurations
  def checkConfig(
      statement: Boolean = false,
      outputString: String = "No error code given, but checkConfig failed."
  ): Unit = {
    if (!statement) {
      print("\n\n\n[error] " + outputString + "\n\n\n")
      System.exit(1)
    }
  }

  // Checking for invalid configurations
  checkConfig(
    (characters % characterFrequencyParallelism) == 0,
    "Error, the character frequency counting parallelism must be a factor of the total number of characters."
  )
  checkConfig(
    (characters % compressionParallelism) == 0,
    "Error, the compression parallelism must be a factor of the total number of characters."
  )
}

class getHuffmanFromCSV() {
  // This takes a dictionary entry and gets the character bits from it.
  def getHuffmanFromCSV(csvFilepath: String): huffmanParameters = {

    var boolMap: Map[String, Boolean] = Map()
    var intMap: Map[String, Int] = Map()
    val file = io.Source.fromFile(csvFilepath)
    for (line <- file.getLines) {
      val cols = line.split(",").map(_.trim)
      if (cols.length == 2) {
        println(s"${cols(0)} = ${cols(1)}")
        if (cols(1) == "true" || cols(1) == "false") {
          boolMap += (cols(0) -> (cols(1) == "true"))
        } else {
          intMap += (cols(0) -> cols(1).toInt)
        }
      } else if(cols.length != 0){
        println("Error, each line should have two values separated by a comma. The line:\n")
        println(line)
        println("\nDid notmeet this requirement")
      }
    }
    file.close

    println("Getting from CSV test was successful")
    val huffmanParametersOutput = new huffmanParameters(
      charactersParam = intMap("characters"),
      characterBitsParam = intMap("characterBits"),
      huffmanTreeCharactersParam = intMap("huffmanTreeCharacters"),
      codewordMaxBitsParam = intMap("codewordMaxBits"),
      outputRegistersParam = boolMap("outputRegisters"),
      inputRegisterParam = boolMap("inputRegister"),
      treeNormalizerInputRegisterParam = boolMap("treeNormalizerInputRegister"),
      characterBufferSizeParam = intMap("characterBufferSize"),
      treeDesiredMaxDepthParam = intMap("treeDesiredMaxDepth"),
      treeDepthInputBitsParam = intMap("treeDepthInputBits"),
      characterFrequencyParallelismParam =
        intMap("characterFrequencyParallelism"),
      compressionParallelismParam = intMap("compressionParallelism"),
      decompressorCharacterBufferSizeParam =
        intMap("decompressorCharacterBufferSize"),
      compressorParallelInputParam = boolMap("compressorParallelInput"),
      compressorParallelOutputParam = boolMap("compressorParallelOutput"),
      compressorInputRegisterParam = boolMap("compressorInputRegister"),
      decompressorParallelInputParam = boolMap("decompressorParallelInput"),
      decompressorParallelOutputParam = boolMap("decompressorParallelOutput"),
      decompressorInputRegisterParam = boolMap("decompressorInputRegister"),
      variableCompressionParam = boolMap("variableCompression"),
      debugStatisticsParam = boolMap("debugStatistics")
    )

    return huffmanParametersOutput
  }
}
