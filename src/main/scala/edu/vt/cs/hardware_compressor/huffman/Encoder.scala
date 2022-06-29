package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.util.StrongWhenOps._


class Encoder(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val treeGeneratorResult = Input(new TreeGeneratorResult(params))
    val in = Flipped(DecoupledStream(params.encoderParallelism,
      UInt(params.characterBits.W)))
    val out = DecoupledStream(params.compressorBitsOut, Bool())
    val finished = Output(Bool())
  })
  
  io.in.ready := 0.U
  io.out.data := DontCare
  io.out.valid := 0.U
  io.out.last := false.B
  
  io.finished := io.out.last && io.out.ready === io.out.valid
  
  val states = Enum(2)
  val metadata :: encode :: Nil = states
  val state = RegInit(UInt(states.length.idxBits.W), metadata)
  
  switch(state) {
  is(metadata) {
    val iteration = RegInit(UInt(params.codeCount.valBits.W), 0.U)
    val outputLength = Wire(UInt())
    io.out.valid := 0.U
    when(io.out.ready >= outputLength) {
      io.out.valid := outputLength
      iteration := iteration + 1.U
    }
    
    when(iteration === 0.U) {
      // write escape character first
      val outData = (io.treeGeneratorResult.escapeCode ##
        io.treeGeneratorResult.escapeCodeLength).asBools
      (io.out.data zip outData).foreach(d => d._1 := d._2)
      outputLength := params.maxCodeLength.valBits.U +&
        io.treeGeneratorResult.escapeCodeLength
    } otherwise {
      // write character codewords
      val code = WireDefault(io.treeGeneratorResult.codes(iteration - 1.U))
      val outData = (code.code ## code.char ## code.codeLength).asBools
      (io.out.data zip outData).foreach(d => d._1 := d._2)
      outputLength := (params.maxCodeLength.valBits + params.characterBits).U +&
        code.codeLength
      
      when(code.codeLength === 0.U) {
        outputLength := 0.U
      }
      when(iteration === params.codeCount.U) {
        // terminate metadata with a zero codeword length
        val outData = 0.U(params.maxCodeLength.valBits.W).asBools
        (io.out.data zip outData).foreach(d => d._1 := d._2)
        outputLength := params.maxCodeLength.valBits.U
        
        when(io.out.ready >= outputLength) {
          // accepted by the consumer
          // move to next state
          state := encode
        }
      }
    }
  }
  is(encode) {
    val codes = (0 until params.encoderParallelism).map{i =>
      val char = io.in.data(i)
      val code = Wire(UInt((params.maxCodeLength + params.characterBits).W))
      val codeLength =
        Wire(UInt((params.maxCodeLength + params.characterBits).valBits.W))
      val codeIndicator = io.treeGeneratorResult.codes
        .map(c => c.char === char && c.codeLength =/= 0.U)
      when(codeIndicator.reduce(_ || _)) {
        // use a regular code
        val c = Mux1H(codeIndicator, io.treeGeneratorResult.codes)
        code := c.code
        codeLength := c.codeLength
      } otherwise {
        // use an escape sequence
        code := io.treeGeneratorResult.escapeCode |
          (char << io.treeGeneratorResult.escapeCodeLength)
        codeLength :=
          io.treeGeneratorResult.escapeCodeLength +& params.characterBits.U
      }
      (code, codeLength)
    }
      .scanLeft((0.U, 0.U, 0.U)){(t, l) => (l._1, t._3, t._3 +& l._2)}
    val outData = codes.map{c => c._1 << c._2}.reduce(_ | _).asBools
    (io.out.data zip outData).foreach(d => d._1 := d._2)
    io.in.ready := OHToUInt(codes.map{c => c._3 <= io.out.ready}.:+(false.B)
      .sliding(2).map{c => c(0) && !c(1)}.toSeq) min io.in.valid
    io.out.valid := VecInit(codes.map(_._3))(io.in.ready)
    io.out.last := io.in.last && io.in.ready === io.in.valid
  }
  }
}
