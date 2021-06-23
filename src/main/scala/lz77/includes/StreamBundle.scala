package lz77.util

import chisel3._

class StreamBundle[I <: Data, O <: Data](inC: Int, inGen: I, outC: Int, outGen: O) extends Bundle {
  val in = Flipped(DecoupledStream(inC, inGen))
  val out = DecoupledStream(outC, outGen)
  
  override def cloneType: this.type =
    new StreamBundle(inC, inGen, outC, outGen).asInstanceOf[this.type]
}
