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
  
  var charsToProcess = Mux(io.charsIn.valid >= (params.camLookahead).U,
    io.charsIn.valid - (params.camLookahead).U, 0.U)
  
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
  
  val matchValids = equalityArray
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
  
  var sColSets = VecInit(equalityArray
    .take(params.camCharsPerCycle)
    .scanRight(Seq.fill(params.camSize)(true.B))
    {(equals, counts) =>
      equals.zip(counts)
        .map{case (e, c) => e && c}
    }
    .init
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
  var io_charsIn_valid = WireDefault(RegEnable(io.charsIn.valid, 0.U, !stall))
  var io_charsIn_finished =
    WireDefault(RegEnable(io.charsIn.finished, false.B, !stall))
  sColSets = RegEnable(RegEnable(sColSets, !stall), !stall)
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  //============================================================================
  
  
  // There are five categories of matches:
  // A-match (all): starts in or before current cycle (includes both N and C)
  // N-match (new): starts in current sycle
  // C-match (continue): starts before current cycle (includes both S and D)
  // S-match (shallow): starts in previous cycle
  // D-match (deep): starts before previous cycle
  
  private class Match extends Bundle {
    val length = UInt(params.camCharsIn.valBits.W)
    val address = UInt(params.camSize.idxBits.W)
  }
  
  // find matches starting this cycle
  private var nMatches = VecInit(matchLengths.map{row =>
    val lenExists =
      WireDefault(VecInit(Seq.fill(params.camCharsIn + 1)(false.B)))
    val addrByLen =
      Wire(Vec(params.camCharsIn + 1, UInt(params.camSize.idxBits.W)))
    addrByLen := DontCare
    val bestMatch = Wire(new Match)
    
    row.zipWithIndex.foreach{case (l, i) =>
      lenExists(l) := true.B
      addrByLen(l) := i.U
    }
    
    lenExists(0) := true.B
    addrByLen(0) := DontCare
    
    bestMatch.length := params.camCharsIn.U - PriorityEncoder(lenExists.reverse)
    bestMatch.address := PriorityMux(lenExists.reverse, addrByLen.reverse)
    bestMatch
  })
  
  // find continued matches
  private val sMatches = VecInit(sColSets.map{cs =>
    val lenExists =
      WireDefault(VecInit(Seq.fill(params.camCharsIn + 1)(false.B)))
    val addrByLen =
      Wire(Vec(params.camCharsIn + 1, UInt(params.camSize.idxBits.W)))
    addrByLen := DontCare
    val bestMatch = Wire(new Match)
    
    matchLengths(0).zip(cs).zipWithIndex.foreach{case ((l, c), i) =>
      when(c) {
        lenExists(l) := true.B
        addrByLen(l) := i.U
      }
    }
    
    lenExists(0) := true.B
    
    bestMatch.length := params.camCharsIn.U - PriorityEncoder(lenExists.reverse)
    bestMatch.address := PriorityMux(lenExists.reverse, addrByLen.reverse)
    bestMatch
  })
  
  val dColSet = Reg(Vec(params.camSize, Bool()))
  
  private val dMatch = {
    val lenExists =
      WireDefault(VecInit(Seq.fill(params.camCharsIn + 1)(false.B)))
    val addrByLen =
      Wire(Vec(params.camCharsIn + 1, UInt(params.camSize.idxBits.W)))
    addrByLen := DontCare
    val bestMatch = Wire(new Match)
    
    matchLengths(0).zip(dColSet).zipWithIndex.foreach{case ((l, c), i) =>
      when(c) {
        lenExists(l) := true.B
        addrByLen(l) := i.U
      }
    }
    
    lenExists(0) := true.B
    
    bestMatch.length := params.camCharsIn.U - PriorityEncoder(lenExists.reverse)
    bestMatch.address := PriorityMux(lenExists.reverse, addrByLen.reverse)
    bestMatch
  }
  
  // these wires are for passing data from stage 3 to stage 2
  val contIsDeep = WireDefault(false.B)
  val contIndex = WireDefault(0.U(params.camCharsPerCycle.idxBits.W))
  
  private var cMatch = Mux(contIsDeep, dMatch, sMatches(contIndex))
  
  dColSet := matchLengths(0)
    .map(_ === charsToProcess)
    .zip(Mux(contIsDeep, dColSet, sColSets(contIndex)))
    .map{l => l._1 && l._2}
  
  when(pushbacknext) {
    stall := true.B
    pushbackprev := true.B
    dColSet := dColSet
  }
  
  //============================================================================
  // PIPELINE STAGE 3
  nMatches = WireDefault(RegEnable(nMatches, !stall))
  cMatch = WireDefault(RegEnable(cMatch, !stall))
  charsToProcess = WireDefault(RegEnable(charsToProcess, 0.U, !stall))
  io_charsIn_valid = WireDefault(RegEnable(io_charsIn_valid, 0.U, !stall))
  io_charsIn_finished =
    WireDefault(RegEnable(io_charsIn_finished, false.B, !stall))
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  //============================================================================
  
  
  val intracycleIdx = RegNext(0.U(params.camCharsPerCycle.idxBits.W), 0.U)
  val continueLength = RegInit(0.U(params.maxCharsToEncode.valBits.W))
  
  val continued = continueLength =/= 0.U
  
  val nMatchesValid = nMatches
    .map(_.length =/= 0.U)
    .zipWithIndex
    .map(v => v._1 && v._2.U >= intracycleIdx)
  val nMatchIdx = PriorityEncoder(nMatchesValid)
  private val nMatch = nMatches(nMatchIdx)
  val nAnyValid = nMatchesValid.reduce(_ || _)
  
  val chosenMatchIdx = Mux(continued, 0.U, nMatchIdx)
  private val chosenMatch = Mux(continued, cMatch, nMatch)
  val chosenMatchValid = Mux(continued, true.B, nAnyValid)
  val preceedingLiterals = chosenMatchIdx - intracycleIdx
  
  // these are sent to stage 2
  contIndex := nMatchIdx
  contIsDeep := continued
  
  io.finished := false.B
  io.matchLength := 0.U
  io.matchCAMAddress := DontCare
  io.literalCount := preceedingLiterals
  continueLength := 0.U
  
  val goesToEnd = chosenMatch.length + chosenMatchIdx === charsToProcess
  val reachMatch = preceedingLiterals <= io.maxLiteralCount
  
  when(!chosenMatchValid) {
    io.literalCount := charsToProcess - intracycleIdx
  }.elsewhen(chosenMatch.length + continueLength >= params.maxCharsToEncode.U) {
    io.matchLength := params.maxCharsToEncode.U - continueLength
    io.matchCAMAddress := chosenMatch.address
    io.literalCount := preceedingLiterals
    when(!io.matchReady) {
      pushbackprev := true.B
      intracycleIdx := chosenMatchIdx
    } otherwise {
      pushbackprev := true.B
      intracycleIdx := chosenMatchIdx + io.matchLength
    }
  }.elsewhen(!goesToEnd) {
    io.matchLength := continueLength + chosenMatch.length
    io.matchCAMAddress := chosenMatch.address
    io.literalCount := preceedingLiterals
    when(!io.matchReady) {
      pushbackprev := true.B
      intracycleIdx := chosenMatchIdx
    } otherwise {
      pushbackprev := true.B
      intracycleIdx := chosenMatchIdx + chosenMatch.length
    }
  }.elsewhen(reachMatch) {
    continueLength := continueLength + chosenMatch.length
    io.literalCount := preceedingLiterals
  } otherwise {
    io.literalCount := preceedingLiterals
  }
  
  when(io.literalCount > io.maxLiteralCount) {
    pushbackprev := true.B
    intracycleIdx := intracycleIdx + io.maxLiteralCount
  }
  
  // todo: handle charsIn.finished
  // todo: output literal characters
  //       (current impl depends on in == literal)
}
