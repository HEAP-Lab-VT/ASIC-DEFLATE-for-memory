package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.util.StrongWhenOps._


class Counter(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(params.counterCharsIn,
      UInt(params.characterBits.W)))
    val result = Output(new CounterResult(params))
    val finished = Output(Bool())
  })
  
  class Entry extends Bundle {
    val char = UInt(params.characterBits.W)
    val freq = UInt(params.passOneSize.valBits.W)
    val high = Bool()
  }
  
  val frequencies = RegInit(VecInit(Seq.fill(params.characterSpace){
    val b = WireDefault(new Entry(), DontCare)
    b.freq := 0.U
    b.high := false.B
    b
  }))
  val total = Reg(UInt(params.passOneSize.valBits.W))
  val updates = Reg(Vec(params.counterCharsIn, new Entry()))
  
  total := total + (io.in.ready min io.in.valid)
  io.in.ready := params.counterCharsIn.U
  for(i <- 0 until params.counterCharsIn) {
    when(i.U < io.in.valid) {
      val char = io.in.data(i)
      val newFreq = frequencies(char).freq +
        PopCount(io.in.data.map(_ === char).take(i)) + 1.U
      frequencies(char).freq := newFreq
      updates(i).char := char
      updates(i).freq := newFreq
    }
  }
  
  
  val highChars = Reg(Vec(params.codeCount - 1, new Entry()))
  val updatedhighChars = WireDefault(highChars)
  highChars := updatedhighChars
  
  // update highChars
  highChars.zip(updatedhighChars).zipWithIndex.foreach{case ((h, uh), i) =>
    val u = PriorityMux(
      updates.map(u => u.char === h.char).reverse :+ true.B,
      updates.reverse :+ h)
    uh.freq := u.freq
  }
  
  
  // do a swap in highChars
  val promotionChar = PriorityEncoder(
    frequencies.map(e => e.freq > highChars.last.freq && !e.high) :+ true.B)
  val promotion = frequencies(promotionChar)
  
  val demotionIdx = PriorityEncoder(
    highChars.init.map(_.freq < highChars.last.freq) :+ true.B)
  val demotion = highChars(demotionIdx)
  
  when(promotionChar === frequencies.length.U) {
    highChars.last := updatedhighChars(demotionIdx)
    highChars(demotionIdx) := updatedhighChars.last
  } otherwise {
    highChars(demotionIdx) := promotion
    highChars(demotionIdx).char := promotionChar
    frequencies(promotionChar).high := true.B
    frequencies(demotion.char).high := false.B
  }
  
  (io.result.highChars zip highChars).foreach{case (r, h) =>
    r.char := h.char
    r.freq := h.freq
  }
  io.result.escapeFreq := total - highChars.map(_.freq).fold(0.U)(_ + _)
  io.finished := io.in.last && io.in.valid === 0.U &&
    promotionChar === frequencies.length.U &&
    demotionIdx === (highChars.length - 1).U
}


class CounterResult(params: Parameters) extends Bundle {
  class Char extends Bundle {
    val char = UInt(params.characterBits.W)
    val freq = UInt(params.passOneSize.valBits.W)
    
    // override def cloneType: this.type =
    //   new Char().asInstanceOf[this.type]
  }
  val highChars = Vec(params.codeCount - 1, new Char())
  val escapeFreq = UInt(params.characterBits.W)
  
  override def cloneType: this.type =
    new CounterResult(params).asInstanceOf[this.type]
}
