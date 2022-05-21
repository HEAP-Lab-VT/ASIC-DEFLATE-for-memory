package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


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
  var active: Bool = null
  var activePrev: Bool = null
  var activeNext: Bool = Wire(Bool())
  var finished: Bool = null
  var finishedPrev: Bool = null
  var finishedNext: Bool = Wire(Bool())
  var transfer: Bool = null
  var transferPrev: Bool = null
  var transferNext: Bool = Wire(Bool())
  def nextStage(activeInit: Bool = false.B): Unit = {
    activePrev = WireDefault(active)
    active = RegInit(Bool(), false.B)
    activeNext := active
    activeNext = WireDefault(Bool(), DontCare)
    
    finishedPrev = WireDefault(finished)
    finished = Wire(Bool())
    finishedNext := finished
    finishedNext = WireDefault(Bool(), DontCare)
    
    transferPrev = WireDefault(transfer)
    transfer = Wire(Bool())
    transferNext := transfer
    transferNext = WireDefault(Bool(), DontCare)
    
    transfer := finishedPrev && activePrev && (!active || transferNext)
    when(transferNext){active := false.B}
    when(transfer){active := true.B}
  }
  
  
  // BEGIN PIPELINE
  active = true.B
  finished = io.in.last && io.in.valid <= io.in.ready
  transfer = WireDefault(Bool(), DontCare)
  
  
  // STAGE 1: Counter
  nextStage(true.B)
  var counterResult = Wire(new CounterResult(params))
  var data = Wire(Vec(params.passOneSize, UInt(params.characterBits.W)))
  var dataLength = Wire(UInt(params.passOneSize.valBits.W))
  var dataComplete = Wire(Bool())
  io.in.restart := transfer
  withReset(transfer || reset.asBool) {
    
    // fetch input data
    val dataReg = Reg(chiselTypeOf(data))
    val dataLengthReg = RegInit(chiselTypeOf(dataLength), 0.U)
    val dataCompleteReg = RegInit(chiselTypeOf(dataComplete), false.B)
    data := dataReg
    dataLength := dataLengthReg
    dataComplete := dataCompleteReg
    for(i <- 0 until params.compressorCharsIn) {
      when(i.U < io.in.ready) {
        dataReg(dataLengthReg + i.U) := io.in.data(i)
      }
    }
    dataLengthReg := dataLengthReg + (io.in.valid min io.in.ready)
    when(io.in.last && io.in.valid <= io.in.ready) {
      dataCompleteReg := true.B
    }
    io.in.ready := (params.passOneSize.U - dataLengthReg) min
      params.compressorCharsIn.U
    
    // count input data
    val counter = Module(new Counter(params))
    
    val dataIndex = RegInit(UInt(params.passOneSize.valBits.W), 0.U)
    dataIndex := dataIndex + (counter.io.in.valid min counter.io.in.ready)
    for(i <- 0 until params.counterCharsIn) {
      counter.io.in.data(i) := dataReg(dataIndex + i.U)
    }
    counter.io.in.valid := (dataLengthReg - dataIndex) min
      params.counterCharsIn.U
    counter.io.in.last :=
      (dataCompleteReg &&
        dataLengthReg - dataIndex <= params.counterCharsIn.U) ||
      (dataLengthReg >= params.passOneSize.U &&
        dataIndex >= ((params.passOneSize - params.counterCharsIn) max 0).U)
    
    counterResult := counter.io.result
    finished := counter.io.finished
  }
  
  
  // STAGE 2: tree generation + encoding
  nextStage()
  counterResult = RegEnable(counterResult, transfer)
  data = RegEnable(data, transfer) // TODO: parameterize the size of this
  dataLength = RegEnable(dataLength, transfer)
  dataComplete = RegEnable(dataComplete, transfer)
  withReset(transfer || reset.asBool) {
    
    val treeGenerator = Module(new TreeGenerator(params))
    treeGenerator.io.counterResult := counterResult
    val treeGeneratorResult = RegNext(treeGenerator.io.result)
    val treeGeneratorFinished = RegNext(treeGenerator.io.finished, false.B)
    
    val dataBegin = RegInit(UInt(params.passOneSize.idxBits.W), 0.U)
    val dataEnd = dataLength
    val dataPointerInverted = RegInit(Bool(), false.B)
    val dataBeginWrap = WireDefault(Bool(), false.B)
    val dataEndWrap = WireDefault(Bool(), false.B)
    when(dataBeginWrap){dataPointerInverted := true.B}
    when(dataEndWrap){dataPointerInverted := false.B}
    when(!dataComplete) {
      // fetch remaining input data
      for(i <- 0 until params.compressorCharsIn) {
        when(i.U < io.in.ready) {
          data((dataEnd + i.U) % params.passOneSize.U) := io.in.data(i)
        }
      }
      
      val dataEndNextNoWrap = dataEnd +& (io.in.valid min io.in.ready)
      dataEndWrap := dataEndNextNoWrap >= params.passOneSize.U
      dataEnd := dataEndNextNoWrap - Mux(dataEndWrap, params.passOneSize.U, 0.U)
      
      val difference = dataEnd - dataBegin
      io.in.ready := Mux(dataPointerInverted,
        -difference,
        params.passOneSize.U - difference
      ) min params.compressorCharsIn.U
      
      when(io.in.last && io.in.valid <= io.in.ready){dataComplete := true.B}
    }
    
    when(treeGeneratorFinished) {
      val encoder = Module(new Encoder(params))
      
      val dataBeginNext = dataBegin +&
        (encoder.io.in.valid min encoder.io.in.ready)
      dataBegin := dataBeginNext - Mux(dataBeginWrap, params.passOneSize.U, 0.U)
      dataBeginWrap := dataBeginNext >= params.passOneSize.U
      for(i <- 0 until params.counterCharsIn) {
        encoder.io.in.data(i) := data(dataBegin + i.U)
      }
      val difference = dataEnd - dataBegin
      encoder.io.in.valid := Mux(dataPointerInverted,
        params.passOneSize.U - difference,
        difference
      ) min params.counterCharsIn.U
      encoder.io.in.last :=
        (dataComplete && dataEnd - dataBegin <= params.encoderParallelism.U) ||
        (dataEnd >= params.passOneSize.U && dataBegin >=
          ((params.passOneSize - params.encoderParallelism) max 0).U)
      
      encoder.io.treeGeneratorResult := treeGeneratorResult
      io.out.data <> encoder.io.out.data
      io.out.valid <> encoder.io.out.valid
      io.out.ready <> encoder.io.out.ready
      io.out.last <> encoder.io.out.last
      finished := encoder.io.finished
    } otherwise {
      io.out.data := DontCare
      io.out.valid := 0.U
      io.out.last := false.B
      finished := false.B
    }
  }
  // make sure outputs have a reasonable default when stage 2 is inactive
  when(!active) {
    io.out.data := DontCare
    io.out.valid := 0.U
    io.out.last := false.B
  }
  
  
  // END PIPELINE
  nextStage()
  active := DontCare
  finished := DontCare
  transfer := io.out.last && io.out.valid <= io.out.ready && io.out.restart
}

object HuffmanCompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanCompressor(params), args)
}
