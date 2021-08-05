package edu.vt.cs.hardware_compressor.lz77

import edu.vt.cs.hardware_compressor.util._
import Parameters._
import chisel3._
import chisel3.util._

class CAM(params: Parameters) extends Module {
  
  val io = IO(new Bundle {
    val charsIn = Flipped(DecoupledStream(
      params.camCharsIn, UInt(params.characterBits.W)))
    
    // Output a match and the number of literals preceeding the match
    val matchCAMAddress = Output(UInt(params.camSize.idxBits.W))
    val matchLength = Output(UInt(params.maxCharsToEncode.valBits.W))
    val matchReady = Input(Bool())
    
    val litOut = DecoupledStream(
      params.camCharsPerCycle, UInt(params.characterBits.W))
    
    val finished = Output(Bool())
  })
  
  
  // pipeline variables
  var stall = WireDefault(false.B)
  var pushbackprev = WireDefault(false.B)
  var pushbacknext = WireDefault(false.B)
  
  
  // This stores the byte history of the CAM.
  val byteHistory = Mem(params.historySize, UInt(params.characterBits.W))
  // This is true iff the camIndex has not yet rolled over
  val camFirstPass = RegInit(true.B)
  // This stores the cam index where the next character will be stored
  val camIndex = RegInit(UInt(params.historySize.idxBits.W), 0.U)
  
  
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
  if(params.camSize.pow2) camIndex := camIndex + io.charsIn.ready
  else camIndex := (camIndex +& io.charsIn.ready) % params.historySize.U
  camFirstPass := camFirstPass &&
    (io.charsIn.ready < params.historySize.U - camIndex)
  
  io.charsIn.ready := params.camCharsPerCycle.U
  
  
  // merge byteHistory and searchPattern for easy matching
  val history =
    (0 until params.camSize)
      .map(_ + params.historySize - params.camSize)
      .map{i => byteHistory(
        if(params.historySize.pow2)
          i.U +% camIndex
        else
          Mux(camIndex < (params.historySize - i).U,
            camIndex +% i.U,
            camIndex -% (params.historySize - i).U)
      )} ++
      io.charsIn.bits
  
  var charsToProcess = Mux(io.charsIn.finished,
    io.charsIn.valid min params.camCharsPerCycle.U,
    Mux(io.charsIn.valid >= params.camLookahead.U,
      io.charsIn.valid - params.camLookahead.U, 0.U))
  
  // find the length of every possible match
  val equalityArray = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) =>
      history
        .zipWithIndex
        .drop(i)
        .take(params.camSize)
        .map{case (hc, hi) => hc === c &&
          (hi.U >= params.camSize.U - camIndex || !camFirstPass)}}
  
  val matchValids = equalityArray
    .zipWithIndex
    map{case (e, i) => e.map(_ && i.U < io.charsIn.valid)}
    .sliding(params.minCharsToEncode)
    .map(_.reduce((a, b) => a.zip(b).map(ab => ab._1 && ab._2)))
    .toSeq
  
  var matchLengths = VecInit(equalityArray
    .take(params.camCharsPerCycle)
    .zipWithIndex
    .map{case (e, i) => e.map(_ && i.U < charsToProcess)}
    .scanRight(Seq.fill(params.camSize)(0.U(params.camCharsIn.valBits.W)))
    {(equals, counts) =>
      equals.zip(counts)
        .map{case (e, c) => Mux(e, c +% 1.U, 0.U)}
    }
    .init
    .zip(matchValids)
    .map(lv => lv._1.zip(lv._2))
    .map(_.map(lv => Mux(lv._2, lv._1, 0.U)))
    .map(v => VecInit(v)))
  
  
  when(pushbacknext) {
    stall := true.B
    pushbackprev := true.B
    io.charsIn.ready := 0.U
  }
  
  
  
  //============================================================================
  // PIPELINE STAGE 2
  matchLengths = WireDefault(RegEnable(matchLengths, !stall))
  charsToProcess = WireDefault(RegEnable(charsToProcess, 0.U, !stall))
  var io_charsIn_bits = WireDefault(RegEnable(io.charsIn.bits, !stall))
  var io_charsIn_valid = WireDefault(RegEnable(io.charsIn.valid, 0.U, !stall))
  var io_charsIn_finished =
    WireDefault(RegEnable(io.charsIn.finished, false.B, !stall))
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  //============================================================================
  
  
  
  var intracycleIndex = RegNext(0.U(params.camCharsPerCycle.idxBits.W), 0.U)
  
  // CAM indexes eligible for continuation
  val continues =
    RegInit(VecInit(Seq.fill(params.camSize)(false.B)))
  // the current length of sequences in the continuation
  val continueLength = RegInit(0.U(params.maxCharsToEncode.valBits.W))
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  var matchIndex = Wire(UInt(params.camCharsPerCycle.idxBits.W))
  var matchLength = Wire(UInt(params.camCharsPerCycle.valBits.W))
  var matchLengthFull = Wire(UInt(params.maxCharsToEncode.valBits.W))
  var matchCAMAddress = Wire(UInt(params.camSize.idxBits.W))
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
        params.camCharsPerCycle.U - PriorityEncoder(lenExists.reverse)
      bestMatch.address := PriorityMux(lenExists.reverse, addrByLen.reverse)
      bestMatch
    }
    
    // find which rows are valid candidates
    val validRows = matchLengths
      .map(_.map(_ =/= 0.U))
      .map(_.reduce(_ || _))
      .zipWithIndex
      .map{case (v, i) => v && i.U >= intracycleIndex}
    
    matchIndex := PriorityEncoder(validRows)
    val m = PriorityMux(validRows, bestMatches)
    matchLength := (
      if(params.maxCharsToEncode < params.camCharsPerCycle)
        m.length min params.maxCharsToEncode.U
      else
        m.length)
    matchCAMAddress := m.address
    matchLengthFull := matchLength
    
    // update continue
    // if it should not continue, it will be reset later this cycle
    continueLength := matchLengthFull
    continues := matchLengths(matchIndex)
      .map(_ + matchIndex === charsToProcess)
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
    
    matchIndex := 0.U
    matchLength :=
      (params.camCharsPerCycle.U - PriorityEncoder(lenExists.reverse)) min
      (params.maxCharsToEncode.U - continueLength)
    matchCAMAddress := PriorityMux(lenExists.reverse, addrByLen.reverse)
    matchLengthFull := matchLength + continueLength
    
    // update continue
    // if it should not continue, it will be reset later this cycle
    continueLength := matchLengthFull
    continues := matchLengths(0)
      .map(_ === charsToProcess)
      .zip(continues)
      .map(c => c._1 && c._2)
  }
  
  intracycleIndex := 0.U
  
  when(matchIndex + matchLength =/= charsToProcess) {
    continueLength := 0.U
    continues := DontCare
    intracycleIndex := matchIndex + matchLength
    pushbackprev := true.B
  }
  
  when(pushbacknext) {
    stall := true.B
    pushbackprev := true.B
    continueLength := continueLength
    continues := continues
    intracycleIndex := intracycleIndex
  }
  
  
  
  //============================================================================
  // PIPELINE STAGE 3
  matchIndex = WireDefault(RegEnable(matchIndex, !stall))
  matchLength = WireDefault(RegEnable(matchLength, !stall))
  matchLengthFull = WireDefault(RegEnable(matchLengthFull, !stall))
  matchCAMAddress = WireDefault(RegEnable(matchCAMAddress, !stall))
  charsToProcess = WireDefault(RegEnable(charsToProcess, 0.U, !stall))
  io_charsIn_bits = WireDefault(RegEnable(io_charsIn_bits, !stall))
  io_charsIn_valid = WireDefault(RegEnable(io_charsIn_valid, 0.U, !stall))
  io_charsIn_finished =
    WireDefault(RegEnable(io_charsIn_finished, false.B, !stall))
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  //============================================================================
  
  
  
  intracycleIndex = RegNext(0.U(params.camCharsPerCycle.idxBits.W), 0.U)
  
  io.finished := false.B
  io.matchLength := matchLengthFull
  io.matchCAMAddress := matchCAMAddress
  
  val finished = io_charsIn_finished && charsToProcess === io_charsIn_valid
  
  pushbackprev := true.B
  intracycleIndex := matchIndex + Mux(io.matchReady, matchLength, 0.U)
  
  when(matchIndex + matchLength === charsToProcess && !finished) {
    io.matchLength := 0.U
    io.matchCAMAddress := DontCare
  }
  
  when(matchIndex + matchLength === charsToProcess || matchLength === 0.U) {
    pushbackprev := false.B
    intracycleIndex := 0.U
  }
  
  io.litOut.valid :=
    Mux(matchLength === 0.U, charsToProcess, matchIndex) - intracycleIndex
  
  when(io.litOut.valid > io.litOut.ready) {
    pushbackprev := true.B
    intracycleIndex := intracycleIndex + io.litOut.ready
  }
  
  for(i <- 0 until params.camCharsPerCycle) {
    io.litOut.bits(i) := io_charsIn_bits(intracycleIndex + i.U)
  }
  
  io.finished := io_charsIn_finished &&
    (matchIndex + matchLength === io_charsIn_valid || matchLength === 0.U)
  io.litOut.finished := io.finished // not used
}
