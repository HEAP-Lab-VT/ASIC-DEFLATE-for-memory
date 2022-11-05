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
  
  val frequencies = RegInit(VecInit(Seq.tabulate(params.characterSpace){i =>
    val b = WireDefault(new Entry(), DontCare)
    b.freq := 0.U
    b.high := false.B
    b.char := i.U
    b
  }))
  val total = RegInit(UInt(params.passOneSize.valBits.W), 0.U)
  val updates = RegInit(VecInit(Seq.fill(params.counterCharsIn){
    val u = WireDefault(new Entry(), DontCare)
    u.freq := 0.U
    u
  }))
  
  total := (total +& io.in.valid) min params.passOneSize.U
  io.in.ready := params.counterCharsIn.U
  for(i <- 0 until params.counterCharsIn) {
    when(i.U < io.in.valid && total < ((params.passOneSize - i) max 0).U) {
      val char = io.in.data(i)
      val newFreq = frequencies(char).freq +
        PopCount(io.in.data.map(_ === char).take(i)) + 1.U
      frequencies(char).freq := newFreq
      updates(i).char := char
      updates(i).freq := newFreq
    } otherwise {
      updates(i).freq := 0.U
    }
  }
  
  
  val highChars = RegInit(VecInit(Seq.fill(params.codeCount - 1){
    val e = WireDefault(new Entry(), DontCare)
    e.freq := 0.U
    e
  }))
  // reflects promotion/demotion
  val shuffledHighChars = WireDefault(highChars)
  highChars := shuffledHighChars
  
  // update highChars
  highChars.zip(shuffledHighChars).zipWithIndex.foreach{case ((h, sh), i) =>
    val u = PriorityMux(
      updates.map(u => u.char === h.char && u.freq =/= 0.U).reverse :+ true.B,
      updates.reverse :+ h
    )
    
    // Prevent writing to a position that is involved in a promotion or
    // demotion. This can make the frequency inaccurate, but it should not
    // deviate too far before it is corrected.
    when(h.char === sh.char && sh.freq =/= 0.U) {
      h.freq := u.freq
    }
  }
  
  
  // find a non-high that is greater than the cutoff
  val promotionChar = PriorityEncoder(
    frequencies.map(e => e.freq > highChars.last.freq && !e.high) :+ true.B)
  val promotion = frequencies(promotionChar)
  
  // find a high that is less than the cutoff
  val demotionIdx = PriorityEncoder(
    highChars.init.map(_.freq < highChars.last.freq) :+ true.B)
  val demotion = highChars(demotionIdx)
  
  when(promotionChar === frequencies.length.U) {
    // perform demotion
    shuffledHighChars.last := highChars(demotionIdx)
    shuffledHighChars(demotionIdx) := highChars.last
  } otherwise {
    // perform promotion and demotion
    shuffledHighChars(demotionIdx) := promotion
    frequencies(promotionChar).high := true.B
    when(demotion.freq =/= 0.U) {
      frequencies(demotion.char).high := false.B
    }
  }
  
  (io.result.highChars zip highChars).foreach{case (r, h) =>
    r.char := h.char
    r.freq := h.freq
  }
  val highTotal = highChars.map(_.freq).fold(0.U)(_ + _)
  io.result.escapeFreq := Mux(highTotal =/= total,
    total - highTotal,
    // if no characters need an escape, set escape frequency to 1 to ensure
    // allocation of escape character in the huffman tree.
    1.U
  )
  io.finished := (io.in.last && io.in.valid === 0.U ||
    total === params.passOneSize.U) &&
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
  val escapeFreq = UInt(params.passOneSize.valBits.W)
}
