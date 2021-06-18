package lz77Parameters

import chisel3._
import chisel3.util._

class lz77Parameters(
    characterBitsParam: Int,
    charactersToCompressParam: Int,
    camCharactersParam: Int,
    camMaxPatternLengthParam: Int,
    camHistoryAvailableParam: Boolean,
    maxPatternLengthParam: Int,
    escapeCharacterParam: Int,
    decompressorMaxCharactersOutParam: Int,
    compressorMaxCharactersParam: Int,
    compressorMaxCharactersOutParam: Int
) {
  // Defined parameters


  // This is the number of bits in a single character of the input.
  var characterBits = characterBitsParam
  // THis is the number of characters to compress in total.
  var charactersToCompress = charactersToCompressParam
  // This is the number of characters of history stored in the CAM.
  var camCharacters = camCharactersParam
  // This is the maximum number of characters in a single character sequence that can be detected in a cycle by the CAM.
  var camMaxPatternLength = camMaxPatternLengthParam
  // This is used to determine whether or not the CAM history is available as an output.
  var camHistoryAvailable = camHistoryAvailableParam
  // This is the maximum number of characters in a single character sequence.
  var maxPatternLength = maxPatternLengthParam
  // This is the character that is used to escape the LZ77 character sequence.
  val escapeCharacter = escapeCharacterParam
  // This is the number of simultaneous characters that the decompressor can output in one clock cycle.
  val decompressorMaxCharactersOut =  decompressorMaxCharactersOutParam
  // This is the number of simultaneous characters that the compressor can process in one clock cycle.
  val compressorMaxCharacters = compressorMaxCharactersParam
  // This is the number of simultaneous characters that the compressor can output in one clock cycle.
  val compressorMaxCharactersOut = compressorMaxCharactersOutParam

  // Derived parameters

  // This is the maximum value a character can hold
  var maxCharacterValue = (1 << characterBits) - 1
  // This is the number of bits required to count the number of characters read.
  var characterCountBits = log2Ceil(charactersToCompress + 1)
  // This is the number of bits needed to count the number of characters in a pattern.
  var patternLengthBits = log2Ceil(maxPatternLength + 1)
  // This is the number of bits needed to represent how many characters are being output by the decompressor
  val decompressorCharactersOutBits = log2Ceil(decompressorMaxCharactersOut + 1)

  // This is the number of bits required to count the number of cam characters.
  var camCharacterCountBits = log2Ceil(camCharacters + 1)
  // This is the number of bits required to address one of the characters in the cam.
  var camAddressBits = log2Ceil(camCharacters)
  // This is the number of bits required to represent the number of characters in a sequence.
  var camCharacterSequenceLengthBits = log2Ceil(camMaxPatternLength + 1)
  // This is a true when the CAM size is a power of 2
  var camSizePow2 = camCharacters == 1 << log2Ceil(camCharacters)

  // This is the number of bits in the shortest possible sequence of characters encoded.
  //                     escape codeword    escape confirmation bit   cam address bits  <- only thing remaining is bits to show the length of the character sequence.
  var minEncodingWidth = characterBits    + 1                       + camAddressBits 
  if(minEncodingWidth % characterBits == 0){
    // If there are no more bits to represent the length of a character sequence, add another character's worth of extra bits in.
    minEncodingWidth = minEncodingWidth + characterBits
  }else{
    minEncodingWidth = minEncodingWidth + (characterBits - (minEncodingWidth % characterBits))
  }
  // This is the number of bits in the minimum encoding width used to identify the length of the pattern.
  // (remember, this isn't the total number of bits, the number represented by these bits is added to the minimum number of characters to encode)
  var minEncodingSequenceLengthBits = minEncodingWidth - characterBits - 1 - camAddressBits
  // This is the minimum number of characters worth encoding as a pattern.
  var minCharactersToEncode = (minEncodingWidth / characterBits)
  // This is the number of bits in the longest possible sequence of characters.
  var maxEncodingWidth = minEncodingWidth
  // This variable is used to make the maxEncodingWidth long enough to encode the maxPatternLength
  private var currentMaxPatternLength = (1 << minEncodingSequenceLengthBits) - 1 + minCharactersToEncode
  while(maxPatternLength > currentMaxPatternLength){
    // Until the maximum number of characters in a pattern can be described in the maxEncodingWidth, we keep adding more bits.
    currentMaxPatternLength += (1 << characterBits)  - 1
    maxEncodingWidth += characterBits
  }
  // This is the maximum number of characters in the longest encoding.
  var maxEncodingCharacterWidths = maxEncodingWidth / characterBits
  // This is the number of characters in an encoding that are used only to denote longer-than-minimum pattern lengths.
  var additionalPatternLengthCharacters = maxEncodingCharacterWidths - minCharactersToEncode
  // This is the number of bits needed to show how many characters is in the output.
  var encodingLengthBits = log2Ceil(maxEncodingCharacterWidths + 1)
  // This is the number of additional pattern characters that can be described with one additional character to the encoding
  var extraCharacterLengthIncrease = (1 << characterBits) - 1
  // This is the number of characters that can be in a pattern of the minimum encoding length
  var maxCharactersInMinEncoding = minCharactersToEncode + ((1 << minEncodingSequenceLengthBits) - 1)
  
  
  var compressorMaxCharactersBits = log2Ceil(compressorMaxCharacters + 1)
  var camMaxCharsIn = compressorMaxCharacters
  var camMaxCharsInBits = log2Ceil(camMaxCharsIn + 1)
  // todo: assert `compressorMaxCharacters <= camMaxPatternLength`
  // todo: assert `compressorMaxCharacters >= minCharactersToEncode`
}



class getLZ77FromCSV() {
  // This checks for errors in the configuration and prints a warning and exits if they are present.
  def checkConfiguration(params: lz77Parameters) {
    if(params.characterBits < 2){
      println("Error, characterBits cannot be less than 2. Exitting.")
      sys.exit(1)
    }
    if(params.camCharacters < 2){
      println("Error, camCharacters cannot be less than 2. Exitting.")
      sys.exit(1)
    }
    if(params.camMaxPatternLength < 2){
      println("Error, camMaxPatternLength cannot be less than 2. Exitting.")
      sys.exit(1)
    }
    if(params.escapeCharacter >= (1 << params.characterBits)){
      println("Error, escapeCharacter cannot be represented with the given number of character bits. Exitting.")
      sys.exit(1)
    }
    if(params.escapeCharacter < 0){
      println("Error, escapeCharacter cannot be negative. Exitting.")
      sys.exit(1)
    }
    if(params.maxEncodingWidth % params.characterBits != 0){
      println("Error, LZ77Simplified max encoding width should always be a multiple of the number of bits of a single character.")
      sys.exit(1)
    }
    if(params.decompressorMaxCharactersOut > params.camMaxPatternLength){
      println("Error, having a decompressor max characters out larger than the CAM max pattern length can cause an issue where characters that haven't been written to the byte history yet are read from the byte history instead of the correct characters.")
      sys.exit(1)
    }
  }

  // This takes a dictionary entry and gets the character bits from it.
  def getLZ77FromCSV(csvFilepath: String): lz77Parameters = {

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
      } else if (cols.length != 0) {
        println("Error, each line should have two values separated by a comma. The line:\n")
        println(line)
        println("\nDid notmeet this requirement")
      }
    }
    file.close

    println("Getting from CSV test was successful")
    val lz77ParametersOutput = new lz77Parameters(
      characterBitsParam = intMap("characterBits"),
      charactersToCompressParam = intMap("charactersToCompress"),
      camCharactersParam = intMap("camCharacters"),
      camMaxPatternLengthParam = intMap("camMaxPatternLength"),
      camHistoryAvailableParam = boolMap("camHistoryAvailable"),
      maxPatternLengthParam = intMap("maxPatternLength"),
      escapeCharacterParam = intMap("escapeCharacter"),
      decompressorMaxCharactersOutParam = intMap("decompressorMaxCharactersOut")
    )

    return lz77ParametersOutput
  }
}
