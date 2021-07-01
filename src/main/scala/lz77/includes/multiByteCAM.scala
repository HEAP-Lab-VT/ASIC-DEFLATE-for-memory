package multiByteCAM

import chisel3._
import chisel3.util._
import lz77Parameters._
import lz77.util._

class multiByteCAM(params: lz77Parameters) extends Module {
  
  val io = IO(new Bundle {
    val charsIn = Flipped(DecoupledStream(
      params.camMaxCharsIn, UInt(params.characterBits.W)))
    val maxLiteralCount = Input(UInt(params.camMaxCharsInBits.W))
    
    // Output a match and the number of literals preceeding the match
    val matchCAMAddress = Output(UInt(params.camAddressBits.W))
    val matchLength = Output(UInt(params.patternLengthBits.W))
    val literalCount = Output(UInt(params.camMaxCharsInBits.W))
    
    val finished = Output(Bool())
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
  
  
  // merge byteHistory and searchPattern for easy matching
  val history =
    (0 until params.camCharacters)
      .map{i => byteHistory(
        if(params.camSizePow2)
          i.U +% camIndex
        else
          Mux(camIndex < (params.camCharacters - i).U,
            camIndex +% i.U,
            camIndex -% (params.camCharacters - i).U)
      )} ++
      io.charsIn.bits
  
  
  // find the length of every possible match
  val matchLengths = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) =>
      history
        .drop(i)
        .take(params.camCharacters)
        .map(_ === c && i.U < io.charsIn.valid)}
    .foldRight
      (Seq.fill(1, params.camCharacters)(0.U(params.camMaxCharsInBits.W)))
      {(equals, counts) =>
        equals
          .zip(counts(0).map(_ + 1.U(params.camMaxCharsInBits.W)))
          .map{case (e, c) =>
            Mux(e, c, 0.U(params.camMaxCharsInBits.W))} +:
        counts
      }
  
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  val matchRow = Wire(Vec(params.camCharacters, UInt(params.characterBits.W)))
  when(continueLength === 0.U) {
    // start a match from scratch
    val row = PriorityMux(
      matchLengths.zipWithIndex.map{case (lens, lit) => (
        lens
          .map(len =>
            len >= params.minCharactersToEncode.U ||
            len === io.in.valid - lit.U)
          .reduce(_ || _),
        new Bundle{val lengths = VecInit(lens); val literalCount = lit.U})})
    
    io.literalCount := row.literalCount
    matchRow := row.lengths
    
  } otherwise {
    // there is a match to continue
    matchRow =: matchLengths(0)
      .zip(continues)
      .map(a => Mux(a._2, a._1, 0.U))
    
    io.literalCount := 0.U
  }
  
  val (matchLength, matchCAMAddress) = matchRow
    .zipWithIndex
    .map{case (len, add) => (len, add.U)}
    .reduce{case ((len1, add1), (len2, add2)) =>
      val is1 = len1 >= len2
      ( Mux(is1, len1, len2),
        Mux(is1, add1, add2))
    }
  
  io.finished := false
  io.matchLength := 0.U
  io.matchCAMAddress := DontCare
  continueLength := 0.U
  continues := DontCare
  
  when(matchLength >= params.minCharactersToEncode.U) {
    when(matchLength + io.literalCount === io.charsIn.valid) {
      when(io.literalCount <= io.maxLiteralCount) {
        continueLength := continueLength + matchLength
        continues := matchRow.map(_ === io.in.valid - io.literalCount)
      }
    } otherwise {
      io.matchLength := continueLength + matchLength
      io.matchCAMAddress := matchCAMAddress
    }
  }
  
  when(io.literalCount <= io.maxLiteralCount) {
    when(matchLength >= params.minCharactersToEncode.U) {
      io.charsIn.ready := io.literalCount + matchLength
    } otherwise {
      io.charsIn.ready := io.literalCount
    }
  } otherwise {
    io.charsIn.ready := io.maxLiteralCount
  }
  
  // handle finished state
  when(io.charsIn.finished) {
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
  }
}

object multiByteCAM extends App {
  val settingsGetter = new getLZ77FromCSV()
  val lz77Config = settingsGetter.getLZ77FromCSV("configFiles/lz77.csv")
  chisel3.Driver
    .execute(Array[String](), () => new multiByteCAM(lz77Config))
}
