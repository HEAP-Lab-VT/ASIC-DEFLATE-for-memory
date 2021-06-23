package multiByteCAM

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77.util._

class multiByteCAM(params: lz77Parameters) extends Module {
  
  val io = IO(new Bundle {
    // This input allows values to be written into the CAM.
    // val writeData = Input(
    //   Vec(params.compressorMaxCharacters, UInt(params.characterBits.W)))
    // val writeDataLength = Input(UInt(params.compressorMaxCharactersBits.W))
    
    // This input allows for search values to be requested from the CAM.
    val charsIn = Flipped(
      DecoupledStream(params.camMaxCharsIn, UInt(params.characterBits.W)))
    val maxLiteralCount = Input(UInt(params.camMaxCharsInBits.W))
    
    // Output a match and the number of literals preceeding the match
    val matchCAMAddress = Output(UInt(params.camAddressBits.W))
    val matchLength = Output(UInt(params.patternLengthBits.W))
    val literalCount = Output(UInt(params.camMaxCharsInBits.W))
    
    val finished = Output(Bool())
    
    // This output is only used when the multiByteCAM is being used by the lz77Compressor.
    // val camHistory =
    //   if (params.camHistoryAvailable)
    //     Some(Output(Vec(params.camCharacters, UInt(params.characterBits.W))))
    //   else None
  })
  
  
  // This stores the byte history of the CAM.
  val byteHistory = Mem(params.camCharacters, UInt(params.characterBits.W))
  // This is true iff the camIndex has not yet rolled over
  val camFirstPass = RegInit(true.B)
  // This stores the cam index where the next character will be stored
  val camIndex = RegInit(UInt(params.camAddressBits.W), 0.U)
  
  
  // CAM indexes eligible for continuation
  val continues =
    RegInit(VecInit(Seq.fill(params.camCharacters)(false.B)))
  // the current length of sequences in the continuation
  val continueLength = RegInit(0.U(log2Ceil(params.maxPatternLength).W))
  
  
  // write data to history
  for(index <- 0 until io.charsIn.bits.length)
    when(index.U < io.charsIn.ready) {
      byteHistory(
        if(params.camSizePow2)
          (camIndex + index.U)(params.camAddressBits - 1, 0)
        else
          (camIndex +& index.U) % params.camCharacters.U
      ) := io.charsIn.bits(index)
    }
  if(params.camSizePow2) camIndex := camIndex + io.charsIn.ready
  else camIndex := (camIndex +& io.charsIn.ready) % params.camCharacters.U
  camFirstPass := camFirstPass &&
    (io.charsIn.ready < params.camCharacters.U - camIndex)
  
  
  // merge byteHistory with searchPattern for easy matching
  val history = Wire(Vec(params.camCharacters + params.camMaxCharsIn,
    UInt(params.characterBits.W)))
  for(i <- 0 until params.camCharacters)
    history(i) := byteHistory(
      if(params.camSizePow2)
        camIndex +% i.U
      else
        Mux(camIndex >= (params.camCharacters - i).U,
          camIndex -% (params.camCharacters - i).U,
          camIndex +% i.U))
  for(i <- 0 until params.camMaxCharsIn)
    history(i + params.camCharacters) := io.charsIn.bits(i)
  
  
  // find the length of every possible match
  val matchLengths = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) => history.drop(i).take(params.camCharacters)
      .map(_ === c && i.U < io.charsIn.valid)}
    .foldRight(Seq(VecInit(Seq.fill(params.camCharacters)(0.U))))
      {(equals, counts) =>
        VecInit(equals.zip(counts(0).map(_ +& 1.U))
          .map{case (e, c) => Mux(e, c, 0.U)}) +: counts
      }
  
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  val matchOptions = Wire(Vec(params.camCharacters,
    UInt(params.camMaxCharsInBits.W)))
  when(continueLength === 0.U) {
    // start a match from scratch
    io.literalCount := PriorityEncoder(matchLengths
      .zipWithIndex
      .map{case (c, i) => (c, params.minCharactersToEncode.U
        min (io.charsIn.valid - i.U))}
      .map{case (c, t) => c
        .map(_ >= t)
        .reduce(_ || _)})
    
    matchOptions := VecInit(matchLengths)(io.literalCount)
  } otherwise {
    // there is a match to continue
    io.literalCount := 0.U
    matchOptions := matchLengths(0)
      .zip(continues)
      .map(a => Mux(a._2, a._1, 0.U))
  }
  
  
  // compute best match length and CAM address
  val (matchLength, matchCAMAddress) = matchOptions.zipWithIndex
    .map{case (l, i) => (l, i.U)}
    .fold((0.U, 0.U)){case ((cl, ci), (ll, li)) =>
      (cl max cl, Mux(cl > cl, ci, li))}
    // .fold((0.U, 0.U)){(c, l) =>
    //   (c._1 max l._1, Mux(c._1 > l._1, c._2, l._2))}
  
  
  // notes on output assertion
  // ===============================
  // behavior:
  // assert continue iff match reaches end and either match length is encodable or continue is already asserted
  // if the match is zero-length at the end, it does not matter whether continue is asserted because it will assert a zero continueLength
  // assert standard matchLength if match is sufficient length and continue is not asserted on previous or next
  // assert continue matchLength if continue is asserted previously but not next
  // assert 0 matchLength if continue is asserted next or match is insufficient length
  // ready is literalCount + match length if length is sufficient or continue is asserted previous
  // ready is literalCount otherwise
  //
  // variables:
  // a: match reaches end
  // b: match length is sufficient
  // c: continue is asserted previous
  // d: continue is asserted next
  // 
  // possible results:
  // A,d: assert continue next
  // B: matchLength is match length
  // C: matchLength is match length + continue length
  // D: matchLength is 0
  // E: ready is literalCount + match length
  // F: ready is literalCount
  // 
  // truth table:
  // a b c | assert
  // ------+----------
  // 0 0 0 | D, F
  // 0 0 1 | C, E
  // 0 1 0 | B or C, E
  // 0 1 1 | C, E
  // 1 0 0 | D, F
  // 1 0 1 | A, D, E
  // 1 1 0 | A, D, E
  // 1 1 1 | A, D, E
  
  
  // assert outputs and continue data
  when(io.charsIn.finished) {
    // handle finished state
    when(continueLength === 0.U) {
      // finish immediately
      io.finished := true.B
      io.charsIn.ready := DontCare
      io.literalCount := DontCare
      io.matchLength := DontCare
      io.matchCAMAddress := DontCare
      continueLength := 0.U
      continues := DontCare
    } otherwise {
      // output continued match before finishing
      io.finished := false.B
      io.charsIn.ready := DontCare
      io.literalCount := 0.U
      io.matchLength := continueLength
      io.matchCAMAddress := PriorityEncoder(continues)
      continueLength := 0.U
      continues := DontCare
    }
  }.elsewhen(io.literalCount > io.maxLiteralCount) {
    // too many literals preceeding the match, do not consume the match
    io.finished := false.B
    io.charsIn.ready := io.maxLiteralCount
    io.matchLength := 0.U
    io.matchCAMAddress := DontCare
    continueLength := 0.U
    continues := DontCare
  }.elsewhen(matchLength < params.minCharactersToEncode.U
      && continueLength === 0.U) {
    // no match
    io.finished := false.B
    io.charsIn.ready := io.literalCount
    io.matchLength := 0.U
    io.matchCAMAddress := DontCare
    continueLength := 0.U
    continues := DontCare
  }.elsewhen(matchLength + io.literalCount === io.charsIn.valid) {
    // match continues to next cycle
    io.charsIn.ready := io.literalCount + matchLength
    io.finished := false.B
    io.matchLength := 0.U
    io.matchCAMAddress := DontCare
    continueLength := continueLength + matchLength
    // make sure at least on character was processed before modifying continue
    when(io.charsIn.valid =/= 0.U) {
      continues := matchOptions.map(_ === matchLength)
    }
  } otherwise {
    // match terminates in this cycle
    io.finished := false.B
    io.charsIn.ready := io.literalCount + matchLength
    io.matchLength := continueLength + matchLength
    io.matchCAMAddress := matchCAMAddress
    continueLength := 0.U
    continues := DontCare
  }
}

object multiByteCAM extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new multiByteCAM(lz77Config))
}
