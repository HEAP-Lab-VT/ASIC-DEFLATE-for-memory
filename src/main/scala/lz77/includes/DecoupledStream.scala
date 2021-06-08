package lz77.util

import chisel3._
import chisel3.util._

// This is adapted from the Chisel source for `chisel3.util.Decoupled`.
class DecoupledStream[T <: Data](count: Int, gen: T)
    extends Bundle {
  
  val ready = Input(UInt(log2Ceil(count + 1).W))
  val valid = Output(UInt(log2Ceil(count + 1).W))
  val bits  = Output(Vec(count, gen))
  val finished = Output(Bool())
  
  override def cloneType: this.type =
    new DecoupledStream(count, gen).asInstanceOf[this.type]
}

object DecoupledStream {
  def apply[T <: Data](count: Int = 0, gen: T = new Bundle {}):
    DecoupledStream[T] = new DecoupledStream(count, gen)
}
