package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util.{RegEnable}
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import java.io.{PrintWriter}
import java.nio.file.Path
import scala.util._


// Note: This module uses push input and pull output to facilitate block-style
//  input and output, so one or more universal connectors may be necessary to
//  avoid deadlock and/or circular logic. See documentation for DecoupledStream.
class HuffmanCompressor(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(RestartableDecoupledStream(params.compressorCharsIn,
      UInt(params.characterBits.W)))
    val out = RestartableDecoupledStream(params.compressorBitsOut, Bool())
  })
  
  
  // DECLARE PIPELINE INFRASTRUCTURE
  var stageId: Int = 0
  var active: Bool = Wire(Bool())
  var activePrev: Bool = null
  var activeNext: Bool = Wire(Bool())
  var finished: Bool = Wire(Bool())
  var finishedPrev: Bool = null
  var finishedNext: Bool = Wire(Bool())
  var transfer: Bool = Wire(Bool())
  var transferPrev: Bool = null
  var transferNext: Bool = Wire(Bool())
  def nextStage(activeInit: Bool = false.B): Unit = {
    stageId += 1
    
    activePrev = WireDefault(active).suggestName(s"activePrev_S$stageId")
    active = RegInit(Bool(), activeInit).suggestName(s"active_S$stageId")
    activeNext := active
    activeNext = WireDefault(Bool(), DontCare)
      .suggestName(s"activeNext_S$stageId")
    
    finishedPrev = WireDefault(finished).suggestName(s"finishedPrev_S$stageId")
    finished = Wire(Bool()).suggestName(s"finished_S$stageId")
    finishedNext := finished
    finishedNext = WireDefault(Bool(), DontCare)
      .suggestName(s"finishedNext_S$stageId")
    
    transferPrev = WireDefault(transfer).suggestName(s"transferPrev_S$stageId")
    transfer = Wire(Bool()).suggestName(s"transfer_S$stageId")
    transferNext := transfer
    transferNext = WireDefault(Bool(), DontCare)
      .suggestName(s"transferNext_S$stageId")
    
    transfer := finishedPrev && activePrev && (!active || transferNext)
    when(transferNext){active := false.B}
    when(transfer){active := true.B}
  }
  
  
  // ACCUMULATE-REPLAY
  val accRep = Module(new AccumulateReplay(params))
  val counterReady = Wire(UInt(params.counterCharsIn.valBits.W))
  accRep.io.in.data := io.in.data
  accRep.io.in.valid := io.in.valid min counterReady
  accRep.io.in.last := io.in.last && io.in.valid === counterReady
  io.in.ready := counterReady min accRep.io.in.ready
  io.in.restart := accRep.io.in.restart
  
  
  // BEGIN PIPELINE
  active := true.B
  finished := io.in.last && io.in.valid <= io.in.ready
  transfer := DontCare
  
  
  // STAGE 1: Counter
  nextStage(true.B)
  io.in.restart := transfer
  var counterResult = Wire(new CounterResult(params))
  withReset(transfer || reset.asBool) {
    val counter = Module(new Counter(params))
    val inbuf = Reg(Vec(params.compressorCharsIn, UInt(params.characterBits.W)))
    val inbufLen = RegInit(UInt(params.compressorCharsIn.valBits.W), 0.U)
    inbuf := io.in.data
    inbufLen := io.in.valid min accRep.io.in.ready
    counter.io.in.data := inbuf
    counter.io.in.valid := inbufLen
    counter.io.in.last := io.in.last && io.in.valid === 0.U
    
    counterReady := params.compressorCharsIn.U -
      Mux(counter.io.in.ready < inbufLen && active,
        inbufLen - counter.io.in.ready, 0.U)
    counterResult := counter.io.result
    finished := counter.io.finished
    
    val inputRestarted = RegInit(Bool(), false.B)
    inputRestarted := inputRestarted || io.in.restart
    when(inputRestarted) {
      counter.io.in.valid := 0.U
      counter.io.in.last := true.B;
      counterReady := 0.U
    }
  }
  
  
  // STAGE 2: tree generation + encoding
  // TODO: split tree generation and encoding into seperate stages
  nextStage()
  accRep.io.out.restart := transfer && RegEnable(true.B, false.B, transfer)
  counterResult = RegEnable(counterResult, transfer)
  val treeGeneratorClock = Wire(Clock())
  val treeGeneratorSafe = Wire(Bool());
  {
    val cl = Reg(Bool())
    cl := !cl
    treeGeneratorClock := cl.asClock
    treeGeneratorSafe := !cl
  }
  val treeGeneratorReset = transfer || reset.asBool || RegNext(transfer, true.B)
  withReset(transfer || reset.asBool) {
    
    val treeGenerator = withClockAndReset(treeGeneratorClock,treeGeneratorReset)
      {Module(new TreeGenerator(params))}
    treeGenerator.io.counterResult := counterResult
    val treeGeneratorResult = RegNext(treeGenerator.io.result)
    val treeGeneratorFinished = RegNext(treeGenerator.io.finished, false.B)
    
    when(treeGeneratorFinished) {
      val encoder =
        withReset(!treeGeneratorFinished || transfer || reset.asBool) {
          Module(new Encoder(params))
        }
      
      encoder.io.in <> accRep.io.out.viewAsDecoupledStream
      encoder.io.treeGeneratorResult := treeGeneratorResult
      io.out.viewAsDecoupledStream <> encoder.io.out
      finished := encoder.io.finished
    } otherwise {
      accRep.io.out.ready := 0.U
      io.out.data := DontCare
      io.out.valid := 0.U
      io.out.last := false.B
      finished := false.B
    }
  }
  // make sure outputs have a reasonable default when stage 2 is inactive
  when(!active) {
    accRep.io.out.ready := 0.U
    io.out.data := DontCare
    io.out.valid := 0.U
    io.out.last := false.B
  }
  
  
  // END PIPELINE
  nextStage()
  active := DontCare
  finished := DontCare
  transfer := io.out.restart // && io.out.last && io.out.valid <= io.out.ready
}

object HuffmanCompressor extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/huffman.csv"))
  params.print()
  Using(new PrintWriter("build/HuffmanParameters.h")){pw =>
    params.genCppDefines(pw, "HUFFMAN_")
  }
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanCompressor(params), args)
}
