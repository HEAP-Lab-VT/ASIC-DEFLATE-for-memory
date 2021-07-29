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
    val matchReady = Input(Bool())
    
    // Output a match and the number of literals preceeding the match
    val matchCAMAddress = Output(UInt(params.camSize.idxBits.W))
    val matchLength = Output(UInt(params.maxCharsToEncode.valBits.W))
    val literalCount = Output(UInt(params.camCharsIn.valBits.W))
    
    val finished = Output(Bool())
  })
  
  
  // This stores the byte history of the CAM.
  val byteHistory = Mem(params.historySize, UInt(params.characterBits.W))
  // This is true iff the camIndex has not yet rolled over
  val camFirstPass = RegInit(true.B)
  // This stores the cam index where the next character will be stored
  val camIndex = RegInit(UInt(params.historySize.idxBits.W), 0.U)
  
  
  // CAM indexes eligible for continuation
  val continues =
    RegInit(VecInit(Seq.fill(params.camSize)(false.B)))
  // the current length of sequences in the continuation
  val continueLength = RegInit(0.U(params.maxCharsToEncode.valBits.W))
  
  
  // write data to history
  for(index <- 0 until io.charsIn.bits.length)
    // when(index.U < io.charsIn.ready) {
      byteHistory(
        if(params.histSizePow2)
          (camIndex + index.U)(params.historySize.idxBits - 1, 0)
        else
          (camIndex +& index.U) % params.historySize.U
      ) := io.charsIn.bits(index)
    // }
  if(params.camSizePow2) camIndex := camIndex + io.charsIn.ready
  else camIndex := (camIndex +& io.charsIn.ready) % params.historySize.U
  camFirstPass := camFirstPass &&
    (io.charsIn.ready < params.historySize.U - camIndex)
  
  
  // merge byteHistory and searchPattern for easy matching
  val history =
    (0 until params.camSize)
      .map(_ + params.historySize - params.camSize)
      .map{i => byteHistory(
        if(params.histSizePow2)
          i.U +% camIndex
        else
          Mux(camIndex < (params.historySize - i).U,
            camIndex +% i.U,
            camIndex -% (params.historySize - i).U)
      )} ++
      io.charsIn.bits
  
  
  // find the length of every possible match
  val equalityArray = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) =>
      history
        .zipWithIndex
        .drop(i)
        .take(params.camSize)
        .map{case (hc, hi) => hc === c && i.U < io.charsIn.valid &&
          (hi.U >= params.camSize.U - camIndex || !camFirstPass)}}
  val matchLengths = equalityArray
    .scanRight(Seq.fill(params.camSize)(0.U(params.camCharsIn.valBits.W)))
      {(equals, counts) =>
        equals.zip(counts)
          .map{case (e, c) => Mux(e, c +% 1.U, 0.U)}
      }
  
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  val matchLength = Wire(UInt(params.camCharsIn.valBits.W))
  val matchCAMAddress = Wire(UInt(params.camSize.idxBits.W))
  val literalCount = Wire(UInt(params.camCharsIn.valBits.W))
  when(continueLength === 0.U) {
    // start a match from scratch
    
    // find best match in each row
    class Match extends Bundle {
      val length = UInt(params.camCharsIn.valBits.W)
      val address = UInt(params.camSize.idxBits.W)
    }
    val bestMatches = matchLengths.map{row =>
      val lenExists =
        WireDefault(VecInit(Seq.fill(params.camCharsIn + 1)(false.B)))
      val addrByLen =
        Wire(Vec(params.camCharsIn + 1, UInt(params.camSize.idxBits.W)))
      addrByLen := DontCare
      
      row.zipWithIndex.foreach{case (l, i) =>
        lenExists(l) := true.B
        addrByLen(l) := i.U
      }
      
      lenExists(0) := true.B
      addrByLen(0) := DontCare
      
      val bestMatch = Wire(new Match)
      bestMatch.length :=
        (params.camCharsIn.U - PriorityEncoder(lenExists.reverse)) min
        (params.maxCharsToEncode.U - continueLength)
      bestMatch.address := PriorityMux(lenExists.reverse, addrByLen.reverse)
      bestMatch
    }
    
    // find which rows are valid candidates
    val validRows = matchLengths.zipWithIndex.map{case (lens, lit) => lens
      .map{len =>
        len >= params.minCharsToEncode.U ||
        len === io.charsIn.valid - lit.U}
      .reduce(_ || _)}
    
    literalCount := PriorityEncoder(validRows)
    val m = PriorityMux(validRows, bestMatches)
    matchLength := m.length
    matchCAMAddress := m.address
    
  } otherwise {
    // there is a match to continue
    
    val lenExists =
      WireDefault(VecInit(Seq.fill(params.camCharsIn + 1)(false.B)))
    val addrByLen =
      Wire(Vec(params.camCharsIn + 1, UInt(params.camSize.idxBits.W)))
    addrByLen := DontCare
    
    matchLengths(0).zip(continues).zipWithIndex.foreach{case ((l, c), i) =>
      when(c) {
        lenExists(l) := true.B
        addrByLen(l) := i.U
      }
    }
    
    lenExists(0) := true.B
    
    matchLength :=
      (params.camCharsIn.U - PriorityEncoder(lenExists.reverse)) min
      (params.maxCharsToEncode.U - continueLength)
    matchCAMAddress := PriorityMux(lenExists.reverse, addrByLen.reverse)
    literalCount := 0.U
  }
  
  
  io.finished := false.B
  io.matchLength := 0.U
  io.matchCAMAddress := DontCare
  io.literalCount := literalCount
  continueLength := 0.U
  continues := DontCare
  
  val endMatch = matchLength === io.charsIn.valid - literalCount
  val continued = continueLength =/= 0.U
  val fullMatch = matchLength >= params.minCharsToEncode.U
  val reachMatch = literalCount <= io.maxLiteralCount
  val ligitMatch = fullMatch || continued
  // NOTES: !endMatch implies ligitMatch
  //        continued implies reachMatch
  
  when(!endMatch) {
    io.matchLength := continueLength + matchLength
    io.matchCAMAddress := matchCAMAddress
  }
  
  when((endMatch || !io.matchReady) &&
      ligitMatch &&
      reachMatch) {
    continueLength := continueLength + matchLength
    continues := VecInit(matchLengths.map(l => VecInit(l)))(literalCount)
      .map(_ === io.charsIn.valid - literalCount)
      .zip(continues.map(_ || !continued))
      .map{case (v, c) => v && c}
  }
  
  when(!reachMatch) {
    io.charsIn.ready := io.maxLiteralCount
  }.elsewhen(ligitMatch) {
    io.charsIn.ready := literalCount + matchLength
  } otherwise {
    io.charsIn.ready := literalCount
  }
  
  when(io.charsIn.finished) {
    // wierd things happen when input runs out because there's no continue
    
    // don't try to continue
    continueLength := 0.U
    continues := DontCare
    
    when(ligitMatch) {
      // push the match (it might otherwise continue)
      io.matchLength := continueLength + matchLength
      io.matchCAMAddress := matchCAMAddress
      
      io.charsIn.ready := Mux(reachMatch,
        Mux(io.matchReady, literalCount + matchLength, literalCount),
        io.maxLiteralCount)
    } otherwise {
      // make match into a literal (it would otherwise wait for more input)
      io.literalCount := io.charsIn.valid
      
      io.charsIn.ready := io.charsIn.valid min io.maxLiteralCount
    }
    
    io.finished := io.charsIn.ready === io.charsIn.valid
  }
}
