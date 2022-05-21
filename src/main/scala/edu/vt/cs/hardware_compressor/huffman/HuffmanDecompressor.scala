package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.util.StrongWhenOps._


// Note: This module uses push input and pull output to facilitate block-style
//  input and output, so one or more universal connectors may be necessary to
//  avoid deadlock and/or circular logic. See documentation for DecoupledStream.
class HuffmanDecompressor(params: Parameters) extends Module {
  class HuffmanCode extends Bundle {
    self =>
    val code = Vec(params.maxCodeLength, Bool())
    val length = UInt(params.maxCodeLength.valBits.W)
    // val mask = Vec(params.maxCodeLength, Bool())
    def mask =
      VecInit(((1.U << length) - 1.U).asBools.take(params.maxCodeLength))
    val character = UInt(params.characterBits.W)
    val escape = Bool()
    // val fullLength =
    //   UInt((params.maxCodeLength + params.characterBits).valBits.W)
    def fullLength = length + Mux(escape, params.characterBits.U, 0.U)
    
    // def postInit(): Unit = {
    //   mask := ((1.U << length) - 1.U).asBools.take(params.maxCodeLength)
    //   fullLength := length + Mux(escape, params.characterBits.U, 0.U)
    // }
    
    def matches(input: Seq[Bool]): Bool = {
      val paddedInput = input.padTo(params.maxCodeLength, false.B)
      length =/= 0.U &&
      (code zip paddedInput zip mask)
        .map{case ((c, i), m) => c === i || ~m}
        .fold(true.B)(_ && _)
    }
    
    def trueCharacter(coded: Seq[Bool]): UInt = Mux(escape,
      (coded.map(_.asUInt).reduce(_ ## _) << length)(
        coded.length - 1,
        (coded.length - params.characterBits) max 0),
      character)
  }
  
  private def decode(coded: Seq[Bool]): HuffmanCode = {
    Mux1H(codes.map(_.matches(coded)), codes)
  }
  
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(params.decompressorBitsIn, Bool()))
    val out = DecoupledStream(params.decompressorCharsOut,
      UInt(params.characterBits.W))
  })
  
  val codes = RegInit(VecInit(Seq.tabulate(params.codeCount){i =>
    val code = WireDefault(new HuffmanCode(), DontCare)
    code.length := 0.U
    code.escape := (i == 0).B
    code
  }))
  
  val states = Enum(2);
  val metadata :: decode :: Nil = states;
  val state = RegInit(UInt(states.length.idxBits.W), metadata)
  
  // set defaults
  io.in.ready := DontCare
  io.out.data := DontCare
  io.out.valid := DontCare
  io.out.last := DontCare
  
  switch(state) {
  is(metadata) {
    val codeIndex = RegInit(UInt(params.codeCount.idxBits.W), 0.U)
    val code = codes(codeIndex)
    val unitLength = Wire(UInt())
    val codeLength = Wire(UInt())
    val valid = unitLength <= io.in.valid
    
    io.out.valid := 0.U
    io.out.last := false.B
    
    codeLength := VecInit(io.in.data.take(params.maxCodeLength.valBits)).asUInt
    code.length := codeLength
    
    when(codeIndex === 0.U) {
      // escape
      unitLength := params.maxCodeLength.valBits.U + codeLength
      code.character := DontCare
      code.code := io.in.data
        .drop(params.maxCodeLength.valBits)
        .take(params.maxCodeLength)
    } otherwise {
      // regular character
      unitLength :=
        (params.maxCodeLength.valBits + params.characterBits).U + codeLength
      code.character := VecInit(io.in.data
        .drop(params.maxCodeLength.valBits)
        .take(params.characterBits))
        .asUInt
      code.code := io.in.data
        .drop(params.maxCodeLength.valBits + params.characterBits)
        .take(params.maxCodeLength)
    }
    when(codeLength === 0.U) {
      unitLength := params.maxCodeLength.valBits.U
    }
    
    when(valid) {
      io.in.ready := unitLength
      codeIndex :@= codeIndex + 1.U
      when(codeLength === 0.U) {
        state := decode
        code := code
      }
    } otherwise {
      io.in.ready := 0.U
      // invalidate writes when the input is not valid
      code := code
    }
  }
  is(decode) {
    // This calculates the bit offsets for all the huffman codes on io.in. it
    // starts by computing the length of every potential code starting at every
    // bit position. Then, for each bit position, it adds the length of the
    // current code plus the length of the next code. It continues in this
    // manner to get the lengths of groups of 2,4,8,16,... codes. The lengths of
    // these groups can be combined to get the bit offset of any huffman code.
    val allDecodes = io.in.data.tails.filter(_.length != 0)
      .map(decode(_)).toSeq
    val allLengths = allDecodes.map(_.fullLength)
    val skipLengths = Iterator.iterate(allLengths)
      {l => (l zip l.tails.toSeq).map(e => e._1 +& VecInit(e._2)(e._1))}
      .map(v => VecInit(v))
    def codeOffset(idx: Int): UInt =
      Iterator.iterate(idx)(_ >> 1).takeWhile(_ != 0)
        .map(_ & 1).map(_ != 0)
        .zip(skipLengths)
        .filter(_._1)
        .map(_._2)
        .foldRight(0.U){(l, i) => i +& l(i)}
    val offsets = (0 to params.decompressorCharsOut).map(codeOffset(_))
    
    val decodedChars = VecInit(allDecodes.zip(io.in.data.tails.toSeq)
      .map(d => d._1.trueCharacter(d._2)))
    
    io.out.data := offsets.init.map(decodedChars(_))
    
    val validCodes = offsets.map(_ >= io.in.valid).tail
    val validCodeCount1H = validCodes
      .+:(true.B)
      .:+(false.B)
      .sliding(2)
      .map(v => v(0) && !v(1))
      .toSeq
    val validCodeCount = OHToUInt(validCodeCount1H)
    
    // min in case params.decompressorCharsOut < params.decompressorParallelism
    io.out.valid := validCodeCount
    io.in.ready := Mux1H(validCodeCount1H, offsets)
  }
  }
}

object HuffmanDecompressor extends App {
  val params = Parameters.fromCSV("configFiles/huffman-compat.csv")
  new chisel3.stage.ChiselStage()
    .emitVerilog(new HuffmanDecompressor(params), args)
}
