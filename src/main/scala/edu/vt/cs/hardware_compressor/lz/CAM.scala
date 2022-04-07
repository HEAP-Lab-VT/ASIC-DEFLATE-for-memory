package edu.vt.cs.hardware_compressor.lz

import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
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
  dontTouch apply stall.suggestName("stall_S1")
  dontTouch apply pushbackprev.suggestName("pushbackprev_S1")
  
  
  // stores the byte history of the CAM.
  val camBuffer = Seq.fill(params.camBufSize)(Reg(UInt(params.characterBits.W)))
  // stores the cam index where the next character will be stored
  val bufferLength = RegInit(UInt(params.camBufSize.valBits.W), 0.U)
  
  
  // number of characters to process in this cycle if no stall
  var charsToProcess = Mux(io.charsIn.finished,
    io.charsIn.valid min params.camCharsPerCycle.U,
    Mux(io.charsIn.valid >= params.camLookahead.U,
      io.charsIn.valid - params.camLookahead.U, 0.U))
  
  io.charsIn.ready := Mux(!stall, charsToProcess, 0.U)
  
  
  // write data to history
  when(!stall) {
    (camBuffer ++ io.charsIn.data)
      .sliding(params.camCharsPerCycle + 1) // TODO: why +1?
      .map(v => VecInit(v)(charsToProcess))
      .zip(camBuffer.iterator) // idk why iterator, Seq extends IterableOnce
      .foreach{case (h, b) => b := h}
    bufferLength := (bufferLength +& charsToProcess) min params.camBufSize.U
  }
  
  
  // merge camBuffer and charsIn for easy matching
  val history = camBuffer.takeRight(params.camSize) ++ io.charsIn.bits
  
  // TODO: use matchValids when computing matchLengths
  // TODO: do not stall when a match ends on the last character to process
  
  // find the length of every possible match
  val equalityArray = io.charsIn.bits
    .zipWithIndex
    .map{case (c, i) =>
      history
        .zipWithIndex
        .drop(i)
        .take(params.camSize)
        .map{case (hc, hi) => hc === c &&
          (params.camSize - hi max 0).U <= bufferLength}}
  
  val matchValids = equalityArray
    .zipWithIndex
    .map{case (e, i) => e.map(_ && i.U < io.charsIn.valid)}
    .sliding(params.minCharsToEncode)
    .map(_.reduce((a, b) => a.zip(b).map(ab => ab._1 && ab._2)))
    .toSeq
  
  var allLengths = VecInit(equalityArray
    .take(params.camCharsPerCycle)
    .zipWithIndex
    .map{case (e, i) => e.map(_ && i.U < charsToProcess)}
    .scanRight(Seq.fill(params.camSize)(0.U(params.camCharsIn.valBits.W)))
    {(equals, counts) =>
      equals.zip(counts)
        .map{case (e, c) => Mux(e, c +% 1.U, 0.U)}
    }
    .init
    .map(l => VecInit(l))
    .toSeq)
  var matchLengths = VecInit(equalityArray
    .zipWithIndex
    .map{case (e, i) => e.map(_ && i.U < io.charsIn.valid)}
    .sliding(params.minCharsToEncode)
    .map(_.reduce((a, b) => a.zip(b).map(ab => ab._1 && ab._2)))
    .toSeq
    .zip(allLengths)
    .map(vl => vl._1.zip(vl._2))
    .map(_.map(vl => Mux(vl._1, vl._2, 0.U)))
    .map(l => VecInit(l)))
  
  
  when(pushbacknext) {
    stall := true.B
    pushbackprev := true.B
  }
  
  dontTouch apply charsToProcess.suggestName("charsToProcess_S1")
  dontTouch apply allLengths.suggestName("allLengths_S1")
  dontTouch apply matchLengths.suggestName("matchLengths_S1")
  
  
  
  //============================================================================
  // PIPELINE STAGE 2
  matchLengths = WireDefault(RegEnable(matchLengths,
    VecInit(Seq.fill(params.camCharsPerCycle, params.camSize)
      (0.U(params.camCharsPerCycle.valBits.W)).map(v => VecInit(v))), !stall))
  allLengths = WireDefault(RegEnable(allLengths,
    VecInit(Seq.fill(params.camCharsPerCycle, params.camSize)
      (0.U(params.camCharsPerCycle.valBits.W)).map(v => VecInit(v))), !stall))
  charsToProcess = WireDefault(RegEnable(charsToProcess,
    0.U(params.camCharsPerCycle.valBits.W), !stall))
  var io_charsIn_bits = WireDefault(RegEnable(io.charsIn.bits, !stall))
  var io_charsIn_valid = WireDefault(RegEnable(io.charsIn.valid,
    0.U(params.camCharsIn.valBits.W), !stall))
  var io_charsIn_finished =
    WireDefault(RegEnable(io.charsIn.finished, false.B, !stall))
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  dontTouch apply stall.suggestName("stall_S2")
  dontTouch apply pushbackprev.suggestName("pushbackprev_S2")
  //============================================================================
  
  
  
  var intracycleIndex = RegNext(0.U(params.camCharsPerCycle.idxBits.W), 0.U)
  
  // CAM indexes eligible for continuation
  val continues =
    RegInit(VecInit(Seq.fill(params.camSize)(false.B)))
  // the current length of sequences in the continuation
  val continueLength = RegInit(0.U(params.maxCharsToEncode.valBits.W))
  
  // find where the match should start in the pattern
  // and rank CAM indexes based on match length
  var matchIndex = Wire(UInt(params.camCharsPerCycle.valBits.W))
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
        WireDefault(VecInit(Seq.fill(params.camCharsPerCycle + 1)(false.B)))
      val addrByLen =
        Wire(Vec(params.camCharsPerCycle + 1, UInt(params.camSize.idxBits.W)))
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
      .map{case (v, i) => v && i.U >= intracycleIndex || i.U === charsToProcess}
      .:+(true.B)
    
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
      WireDefault(VecInit(Seq.fill(params.camCharsPerCycle + 1)(false.B)))
    val addrByLen =
      Wire(Vec(params.camCharsPerCycle + 1, UInt(params.camSize.idxBits.W)))
    addrByLen := DontCare
    
    allLengths(0).zip(continues).zipWithIndex.foreach{case ((l, c), i) =>
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
  
  // don't save the continue if it is end of input
  when(io_charsIn_finished && charsToProcess === io_charsIn_valid) {
    continueLength := 0.U
    continues := DontCare
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
  
  dontTouch apply matchLengths.suggestName("matchLengths_S2")
  dontTouch apply allLengths.suggestName("allLengths_S2")
  dontTouch apply charsToProcess.suggestName("charsToProcess_S2")
  dontTouch apply intracycleIndex.suggestName("intracycleIndex_S2")
  dontTouch apply matchIndex.suggestName("matchIndex_S2")
  dontTouch apply matchLength.suggestName("matchLength_S2")
  dontTouch apply matchLengthFull.suggestName("matchLengthFull_S2")
  dontTouch apply matchCAMAddress.suggestName("matchCAMAddress_S2")
  dontTouch apply io_charsIn_bits.suggestName("io_charsIn_bits_S2")
  dontTouch apply io_charsIn_valid.suggestName("io_charsIn_valid_S2")
  dontTouch apply io_charsIn_finished.suggestName("io_charsIn_finished_S2")
  
  
  
  //============================================================================
  // PIPELINE STAGE 3
  matchIndex = WireDefault(RegEnable(matchIndex,
    0.U(params.camCharsPerCycle.valBits.W), !stall))
  matchLength = WireDefault(RegEnable(matchLength,
    0.U(params.camCharsPerCycle.valBits.W), !stall))
  matchLengthFull = WireDefault(RegEnable(matchLengthFull,
    0.U(params.maxCharsToEncode.valBits.W), !stall))
  matchCAMAddress = WireDefault(RegEnable(matchCAMAddress, !stall))
  charsToProcess = WireDefault(RegEnable(charsToProcess,
    0.U(params.camCharsPerCycle.valBits.W), !stall))
  io_charsIn_bits = WireDefault(RegEnable(io_charsIn_bits, !stall))
  io_charsIn_valid = WireDefault(RegEnable(io_charsIn_valid,
    0.U(params.camCharsIn.valBits.W), !stall))
  io_charsIn_finished =
    WireDefault(RegEnable(io_charsIn_finished, false.B, !stall))
  
  stall = WireDefault(false.B)
  pushbackprev = pushbacknext
  pushbacknext = WireDefault(false.B)
  dontTouch apply stall.suggestName("stall_S3")
  dontTouch apply pushbackprev.suggestName("pushbackprev_S3")
  //============================================================================
  
  
  
  intracycleIndex = RegNext(intracycleIndex, 0.U)
  
  io.finished := false.B
  io.matchLength := matchLengthFull
  io.matchCAMAddress := matchCAMAddress
  
  val finished = io_charsIn_finished && charsToProcess === io_charsIn_valid
  
  when(matchIndex + matchLength === charsToProcess && !finished) {
    io.matchLength := 0.U
    io.matchCAMAddress := DontCare
  }
  
  io.litOut.valid := matchIndex - intracycleIndex
  
  when(!io.matchReady && matchLength =/= 0.U) {
    pushbackprev := true.B
    intracycleIndex := matchIndex
  }
  
  when(io.litOut.valid > io.litOut.ready) {
    pushbackprev := true.B
    intracycleIndex := intracycleIndex + io.litOut.ready
  }
  
  for(i <- 0 until params.camCharsPerCycle) {
    io.litOut.bits(i) := io_charsIn_bits(intracycleIndex + i.U)
  }
  
  io.finished := io_charsIn_finished &&
    matchIndex + matchLength >= io_charsIn_valid
  io.litOut.finished := io.finished // not used
  
  dontTouch apply intracycleIndex.suggestName("intracycleIndex_S3")
  dontTouch apply matchIndex.suggestName("matchIndex_S3")
  dontTouch apply matchLength.suggestName("matchLength_S3")
  dontTouch apply matchLengthFull.suggestName("matchLengthFull_S3")
  dontTouch apply matchCAMAddress.suggestName("matchCAMAddress_S3")
  dontTouch apply charsToProcess.suggestName("charsToProcess_S3")
  dontTouch apply io_charsIn_bits.suggestName("io_charsIn_bits_S3")
  dontTouch apply io_charsIn_valid.suggestName("io_charsIn_valid_S3")
  dontTouch apply io_charsIn_finished.suggestName("io_charsIn_finished_S3")
}
