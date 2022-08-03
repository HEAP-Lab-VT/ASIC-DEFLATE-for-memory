package edu.vt.cs.hardware_compressor.huffman

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._
import edu.vt.cs.hardware_compressor.util.StrongWhenOps._


class TreeGenerator(params: Parameters) extends Module {
  val io = IO(new Bundle{
    val counterResult = Input(new CounterResult(params))
    val result = Output(new TreeGeneratorResult(params))
    val finished = Output(Bool())
  })
  
  class Root extends Bundle {
    val freq = UInt(params.passOneSize.valBits.W)
  }
  class Leaf extends Bundle {
    val code = UInt(params.maxCodeLength.W)
    val codeLength = UInt(params.maxCodeLength.valBits.W)
    val root = UInt(params.codeCount.idxBits.W)
  }
  class IndexedRoot extends Root {
    val idx = UInt(params.codeCount.idxBits.W)
  }
  
  val leaves = RegInit(VecInit((0 until params.codeCount).map{i =>
    val l = Wire(new Leaf())
    l.code := 0.U
    l.codeLength := 0.U
    l.root := i.U
    l
  }))
  val roots = Reg(Vec(params.codeCount, new Root()))
  
  val leavesNext = WireDefault(leaves)
  leaves := leavesNext
  val rootsInit = Mux(RegNext(true.B, false.B), roots, VecInit(
    io.counterResult.highChars.map(_.freq).:+(io.counterResult.escapeFreq)
      .map{f =>
        val r = Wire(new Root())
        r.freq := f
        r
      }
  ))
  roots := rootsInit
  
  // finds the two roots with the lowest frequency
  val least = Iterator.iterate(
    rootsInit
    .map(_.freq)
    // add a dummy in case there are an odd number of candidate roots
    .:+(0.U(params.passOneSize.valBits.W))
    // invoke unsigned integer underflow to make 0-frequency (i.e. invalid)
    // characters not be chosen
    .map(_ -% 1.U)
    .zipWithIndex.map{r =>
      val b = Wire(new IndexedRoot())
      b.freq := r._1
      b.idx := r._2.U
      b
    }
    // group candidates in pairs
    .grouped(2)
    // drop the last (dummy) element if it was not paired
    .filter(_.length == 2)
    // order elements within each pair by frequency 
    .map{r2 =>
      Seq(
        Mux(r2(0).freq <= r2(1).freq, r2(0), r2(1)),
        Mux(r2(0).freq <= r2(1).freq, r2(1), r2(0))
      )
    }.toSeq
  ){r2 =>
    r2.grouped(2).map(_.reduce{(a, b) =>
      // merge two pairs by selecting the two with lowest frequency
      Seq(
        Mux(a(0).freq <= b(0).freq, a(0), b(0)),
        Mux(a(0).freq <= b(0).freq,
          Mux(a(1).freq <= b(0).freq, a(1), b(0)),
          Mux(a(0).freq <= b(1).freq, a(0), b(1))
        )
      )
    }).toSeq
  }
  .find(_.length == 1)
  .get.head
  // correct subtraction by 1 from before
  .map(r => {
    val ir = WireDefault(r)
    ir.freq := r.freq + 1.U
    ir
  })
  
  when(least(1).freq =/= 0.U) {
    // merge the two roots into one
    val newRoot = Wire(new Root())
    newRoot.freq := least(0).freq + least(1).freq
    roots(least(0).idx) := newRoot
    roots(least(1).idx).freq := 0.U
  }
  
  val least_S2 = Seq.fill(2)(RegInit({
    val ir = WireDefault(new IndexedRoot(), DontCare)
    ir.freq := 0.U
    ir
  }))
  least.zip(least_S2).foreach(l => l._2 := l._1)
  
  when(least_S2(1).freq =/= 0.U) {
    // update the leaves of the two roots
    leaves.zip(leavesNext).foreach{case (l, n) =>
      when(l.root === least_S2(0).idx) {
        n.code := l.code << 1
        n.codeLength := l.codeLength + 1.U
      }
      when(l.root === least_S2(1).idx) {
        n.code := l.code << 1 | 1.U
        n.codeLength := l.codeLength + 1.U
        n.root := least_S2(0).idx
      }
      // depth truncation
      when(least_S2.map(_.idx === l.root).reduce(_ || _) &&
        l.codeLength === params.maxCodeLength.U
      ) {
        when((l.code(params.maxCodeLength - 1) === false.B ||
          l.code(params.maxCodeLength - 2, 0) =/= leaves.last.code) &&
          (l ne leaves.last).B
        ) {
          n.code := DontCare
          n.codeLength := 0.U
          n.root := least_S2(1).idx
        } otherwise {
          n.codeLength := params.maxCodeLength.U
        }
      }
    }
  }
  
  // output
  (io.result.codes lazyZip leavesNext lazyZip io.counterResult.highChars)
  .foreach{(r, l, h) =>
    r.char := h.char
    r.code := l.code
    r.codeLength := l.codeLength
  }
  io.result.escapeCode := leavesNext.last.code
  io.result.escapeCodeLength := leavesNext.last.codeLength
  io.finished := PopCount(rootsInit.map(_.freq =/= 0.U)) === 1.U
  // io.finished := leavesNext.map(_.root == leavesNext.head.root)
  //   .reduce(_ && _)
}


class TreeGeneratorResult(params: Parameters) extends Bundle {
  class Code extends Bundle {
    val char = UInt(params.characterBits.W)
    val code = UInt(params.maxCodeLength.W)
    val codeLength = UInt(params.maxCodeLength.valBits.W)
    
    // override def cloneType: this.type =
    //   new Code().asInstanceOf[this.type]
  }
  val codes = Vec(params.codeCount - 1, new Code())
  val escapeCode = UInt(params.maxCodeLength.W)
  val escapeCodeLength = UInt(params.maxCodeLength.valBits.W)
}
