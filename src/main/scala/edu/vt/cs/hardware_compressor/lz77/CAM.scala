package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import Parameters._
import chisel3._
import chisel3.util._

class CAM(params: Parameters) extends Module {
  
  val io = IO(new Bundle {
    val charsIn = Flipped(DecoupledStream(
      params.camCharsIn, UInt(params.characterBits.W)))
    val maxLiteralCount = Input(UInt(params.camCharsIn.valBits.W))
    
    // Output a match and the number of literals preceeding the match
    val matchCAMAddress = Output(UInt(params.camSize.idxBits.W))
    val matchLength = Output(UInt(params.maxCharsToEncode.valBits.W))
    val literalCount = Output(UInt(params.camCharsIn.valBits.W))
    
    val finished = Output(Bool())
  })
  
  
  // This stores the byte history of the CAM.
  val byteHistory = Mem(params.camSize, UInt(params.characterBits.W))
  // This is true iff the camIndex has not yet rolled over
  val camFirstPass = RegInit(true.B)
  // This stores the cam index where the next character will be stored
  val camIndex = RegInit(UInt(params.camSize.idxBits.W), 0.U)
  
  
  // CAM indexes eligible for continuation
  val continues =
    RegInit(VecInit(Seq.fill(params.camSize)(false.B)))
  // the current length of sequences in the continuation
  val continueLength = RegInit(0.U(params.maxCharsToEncode.valBits.W))
  
  
  // write data to history
  for(index <- 0 until io.charsIn.bits.length)
    when(index.U < io.charsIn.ready) {
      byteHistory(
        if(params.camSizePow2)
          (camIndex + index.U)(params.camSize.idxBits - 1, 0)
        else
          (camIndex +& index.U) % params.camSize.U
      ) := io.charsIn.bits(index)
    }
  if(params.camSizePow2) camIndex := camIndex + io.charsIn.ready
  else camIndex := (camIndex +& io.charsIn.ready) % params.camSize.U
  camFirstPass := camFirstPass &&
    (io.charsIn.ready < params.camSize.U - camIndex)
  
  
  // merge byteHistory and searchPattern for easy matching
  val history =
    (0 until params.camSize)
      .map{i => byteHistory(
        if(params.camSizePow2)
          i.U +% camIndex
        else
          Mux(camIndex < (params.camSize - i).U,
            camIndex +% i.U,
            camIndex -% (params.camSize - i).U)
      )} ++
      io.charsIn.bits
  
  
  // find the length of every possible match
  val matchLengths = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) =>
      history
        .drop(i)
        .take(params.camSize)
        .map(_ === c && i.U < io.charsIn.valid)}
    .foldRight(
      Seq.fill(1, params.camSize)(0.U(params.camCharsIn.valBits.W)))
      {(equals, counts) =>
        equals
          .zip(counts(0).map(_ + 1.U(params.camCharsIn.valBits.W)))
          .map{case (e, c) =>
            Mux(e, c, 0.U(params.camCharsIn.valBits.W))} +:
        counts
      }
  
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  val matchRow =
    Wire(Vec(params.camSize, UInt(params.camCharsIn.valBits.W)))
  val literalCount = Wire(UInt(params.camCharsIn.valBits.W))
  when(continueLength === 0.U) {
    // start a match from scratch
    
    class Row extends Bundle {
      val row = Vec(params.camSize, UInt(params.characterBits.W))
      val lit = UInt(params.camCharsIn.valBits.W)
    }
    
    val row = PriorityMux(
      matchLengths.zipWithIndex.map{case (lens, lit) =>
        val curRow = Wire(new Row)
        curRow.row := VecInit(lens)
        curRow.lit := lit.U
        ( lens
            .map(len =>
              len >= params.minCharsToEncode.U ||
              len === io.charsIn.valid - lit.U)
            .reduce(_ || _),
          curRow)})
    
    literalCount := row.lit
    matchRow := row.row
    
  } otherwise {
    // there is a match to continue
    matchRow := matchLengths(0)
      .zip(continues)
      .map(a => Mux(a._2, a._1, 0.U))
    
    literalCount := 0.U
  }
  
  val (nolimitMatchLength, matchCAMAddress) = matchRow
    .zipWithIndex
    .map{case (len, add) => (len, add.U)}
    .reduce[(UInt, UInt)]{case ((len1, add1), (len2, add2)) =>
      val is1 = len1 >= len2
      ( Mux(is1, len1, len2),
        Mux(is1, add1, add2))
    }
  val matchLength =
    nolimitMatchLength min (params.maxCharsToEncode.U - continueLength)
  
  io.finished := false.B
  io.matchLength := 0.U
  io.matchCAMAddress := DontCare
  io.literalCount := literalCount
  continueLength := 0.U
  continues := DontCare
  
  when(continueLength =/= 0.U || matchLength >= params.minCharsToEncode.U) {
    when(matchLength + literalCount =/= io.charsIn.valid ||
        io.charsIn.finished) {
      io.matchLength := continueLength + matchLength
      io.matchCAMAddress := Mux(matchLength === 0.U,
        PriorityEncoder(continues),
        matchCAMAddress)
    }.elsewhen(literalCount <= io.maxLiteralCount) {
      continueLength := continueLength + matchLength
      continues := matchRow.map(_ === io.charsIn.valid - literalCount)
    }
  }.elsewhen(io.charsIn.finished) {
    io.literalCount := literalCount + matchLength
  }
  
  when(continueLength =/= 0.U) {
    io.charsIn.ready := matchLength
  }.elsewhen(io.literalCount > io.maxLiteralCount) {
    io.charsIn.ready := io.maxLiteralCount
  }.elsewhen(matchLength >= params.minCharsToEncode.U) {
    io.charsIn.ready := literalCount + matchLength
  } otherwise {
    io.charsIn.ready := io.literalCount
  }
  
  // compute finished
  io.finished := io.charsIn.finished && io.charsIn.ready === io.charsIn.valid
}
