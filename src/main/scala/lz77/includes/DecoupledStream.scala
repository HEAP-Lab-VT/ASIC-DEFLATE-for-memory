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


class ReadyDecoupler[T <: Data](inSize: Int, outSize: Int, gen: T)
    extends Module {
  val io = IO(new StreamBundle(inSize, gen, outSize, gen))
  
  val buffer = Reg(Vec(outSize, gen))
  val bufferLength = RegInit(0.U(log2Ceil(outSize + 1).W))
  
  for(i <- 0 until outSize)
    when(i.U +& io.out.ready < bufferLength) {
      buffer(i) := buffer(i.U + io.out.ready)
    } otherwise {
      buffer(i) := io.in.bits(i.U + io.out.ready - bufferLength)
    }
  bufferLength := Mux(io.out.ready < io.out.valid,
    io.out.valid - io.out.ready, 0.U)
  
  for(i <- 0 until outSize)
    io.out.bits(i) :=
      Mux(i.U < bufferLength, buffer(i), io.in.bits(i.U - bufferLength))
  
  io.out.valid := Mux(io.in.finished, bufferLength,
    (bufferLength +& io.in.valid) min outSize.U)
  io.in.ready := outSize.U - bufferLength
  io.out.finished := io.in.finished && bufferLength === 0.U
}

object ReadyDecoupler {
  def apply[T <: Data](
      inSize: Int = 0,
      outSize: Int = 0,
      gen: T = new Bundle {}):
      ReadyDecoupler[T] =
    new ReadyDecoupler(inSize, outSize, gen)
}
