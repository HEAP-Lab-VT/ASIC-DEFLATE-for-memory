package lz77.util

import chisel3._
import chisel3.util._


/**
 * `DecoupledStream` is an adaption of `chisel3.util.Decoupled` that adds the
 * capability to specify *how many* elements are ready or valid.
 * 
 * A DecoupledStream has two parts: a producer and a consumer. The producer
 * is effectively "the output" of a stream of data, and the consumer is the
 * receiver of the data. The producer uses the DecoupledStream as is, and the
 * consumer uses the DecoupledStream flipped. The number of data elements that
 * are passed through the interface in a cycle is the minimum of the ready and
 * valid signals. The bits signal carries the data elements, and at least as
 * many elements must be valid that are specified by the valid signal.
 *
 * The finished signal is asserted by the producer only if there are no more
 * data elements beyond the currently valid elements. A producer is technically
 * allowed to wait to assert this signal until all data is passed through;
 * however, this behavour could cause deadlock if the consumer accepts data in
 * chunks, so producers are encouraged to assert the signal as soon as possible.
 * 
 * There are two types of producers and consumers: 'push' and 'pull'. Sometimes,
 * a push producer is called a 'compatable' producer and a pull consuer is
 * called a 'compatable' consumer.
 * - A pull producer is wired such that the valid (or bits) signal is dependent
 *   on the ready signal. Effectively, this requires the consumer to "pull" data
 *   out of the producer.
 * - A push (a.k.a. compatable) producer asserts the valid (and bits) signal
 *   independent of the ready signal. Effectively, this "pushes" data to the
 *   consumer (and the consumer decides how much to accept).
 * - A push consumer is wired such that the ready signal is dependent on the
 *   valid (or bits) signal. This arrangement requires the producer to "push"
 *   data to the consumer.
 * - A pull (a.k.a. compatable) consumer asserts the ready signal independent of
 *   the valid (and bits) signal. This effectively "pulls" data from the
 *   producer.
 * A compatable type can be safely attached to both push and pull types i.e. the
 * only unsafe attachment is between a pull producer and a push consumer.
 * Connecting a pull producer and a push consumer is very likely to create
 * circular combinational logic. If a pull producer and a push consumer must be
 * connected, then a `UniversalConnector` may be placed between the two
 * components as a compatability layer.
 */
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


/**
 * A stream pipeline module that passes data through unchanged and has
 * compatable DecoupledStream interfaces on both sides. That is, this module
 * is a pull (i.e. compatable) consumer and a push (i.e. compatable) producer.
 * This module can be used as a compatability layer to connect a push consumer
 * with a pull producer.
 * 
 * This module may also be used to convert between DecoupledStream interfaces of
 * different sizes. This behavour can be particularly useful when a consumer
 * accepts chuncked or lookahead data since this module will act as a buffer to
 * facilitate such a consumer
 */
class UniversalConnector[T <: Data](inSize: Int, outSize: Int, gen: T)
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
  
  io.out.valid := (bufferLength +& io.in.valid) min outSize.U
  io.in.ready := outSize.U - bufferLength
  io.out.finished := io.in.finished && io.in.ready >= io.in.valid
}

object UniversalConnector {
  def apply[T <: Data](
      inSize: Int = 0,
      outSize: Int = 0,
      gen: T = new Bundle {}):
      UniversalConnector[T] =
    new UniversalConnector(inSize, outSize, gen)
}
