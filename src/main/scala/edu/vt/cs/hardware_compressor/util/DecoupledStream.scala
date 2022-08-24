package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._


/**
 * `DecoupledStream` is an adaption of `chisel3.util.Decoupled` that adds the
 * capability to specify *how many* elements are ready or valid.
 * 
 * A DecoupledStream has two parts: a producer and a consumer. The producer
 * is effectively "the output" of a stream of data, and the consumer is the
 * receiver of that data. The producer uses the DecoupledStream as is, and the
 * consumer uses the DecoupledStream flipped. The number of data elements that
 * are passed through the interface in a cycle is the minimum of the ready and
 * valid signals. The bits signal carries the data elements, and at least as
 * many elements must be valid that are specified by the valid signal.
 *
 * The 'last' signal is asserted by the producer only if there are no more data
 * elements beyond the currently valid elements. A producer is technically
 * allowed to wait to assert this signal until all data is passed through;
 * however, this behavour could cause deadlock if the consumer accepts data in
 * chunks, so producers are encouraged to assert the signal as soon as possible.
 * 
 * There are two types of producers and consumers: 'push' and 'pull'. Sometimes,
 * a push producer is called a 'compatable' producer and a pull consumer is
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
  val data = Output(Vec(count, gen))
  def bits = data // alias legacy name
  val last = Output(Bool())
  def finished = last // alias legacy name
}

object DecoupledStream {
  def apply[T <: Data](count: Int = 0, gen: T = new Bundle{}):
    DecoupledStream[T] = new DecoupledStream(count, gen)
}


/**
 * A DecoupledStream which allows a new stream to be started after a stream has
 * finished.
 * 
 * This is accomplished by adding a `restart` Bool signal from consumer to
 * producer. When `restart` is asserted, `last` is asserted, and `ready` is
 * greater than or equal to `valid`, then the consumer will be ready for the
 * next stream on the next cycle and the next cycle will start a new stream.
 * Behavior is implementation-defined if `restart` is asserted when `last` is
 * deasserted or `ready` is less than `valid`.
 * 
 * If the producer is not yet ready to produce the next stream when a restart
 * occures, then it may assert `last := false.B` and `valid := 0.U` until it is
 * ready to produce. To avoid terminating the new stream prematurely, the
 * producer should take care to deassert `last` on the cycle following a
 * `restart` assertion (unless of course the stream is so short that it should
 * be terminated at that point).
 */
class RestartableDecoupledStream[T <: Data](count: Int, gen: T)
    extends DecoupledStream[T](count: Int, gen: T) {
  val restart = Input(Bool())
  
  def viewAsDecoupledStream: DecoupledStream[T] =
    this.viewAsSupertype(new DecoupledStream(count, gen))
}

object RestartableDecoupledStream {
  def apply[T <: Data](count: Int = 0, gen: T = new Bundle{}):
    RestartableDecoupledStream[T] = new RestartableDecoupledStream(count, gen)
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
 * accepts chunked or lookahead data since this module will act as a buffer to
 * facilitate such a consumer
 */
class UniversalConnector[T <: Data](inSize: Int, outSize: Int, gen: T)
    extends StreamBuffer[T](inSize, outSize, outSize, gen, false, true) {}

object UniversalConnector {
  def apply[T <: Data](
      inSize: Int = 0,
      outSize: Int = 0,
      gen: T = new Bundle {}):
      UniversalConnector[T] =
    new UniversalConnector(inSize, outSize, gen)
}


class StreamBundle[I <: Data, O <: Data](inSize: Int, inGen: I, outSize: Int,
    outGen: O) extends Bundle {
  val in = Flipped(DecoupledStream(inSize, inGen))
  val out = DecoupledStream(outSize, outGen)
}


class StreamBuffer[T <: Data](inSize: Int, outSize: Int, bufSize: Int, gen: T,
    delayForward: Boolean = false, delayBackward: Boolean = false)
    extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(inSize, gen))
    val out = DecoupledStream(outSize, gen)
  })
  
  val tee = Module(new StreamTee[T](inSize, Seq(outSize), bufSize, gen,
    delayForward, delayBackward))
  
  io.in <> tee.io.in
  io.out <> tee.io.out(0)
}


class StreamTee[T <: Data](inSize: Int, outSizes: Seq[Int], bufSize: Int,
    gen: T, delayForward: Boolean = false, delayBackward: Boolean = false)
    extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(inSize, gen))
    val out = MixedVec(outSizes.map(s => DecoupledStream(s, gen)))
  })
  
  if(outSizes.isEmpty) throw new IllegalArgumentException("no outputs")
  val singleOut = outSizes.length == 1
  
  val buffer = Reg(Vec(bufSize, gen))
  val bufferLength = RegInit(0.U(bufSize.valBits.W))
  val offsets = Seq.fill(outSizes.length)(RegInit(0.U(bufSize.valBits.W)))
  val last = RegInit(Bool(), false.B)
  
  val offReady =
    io.out.zip(offsets).map(o => o._1.ready +& o._2).reduce(_ min _)
  val validLength =
    if(delayBackward)
      (bufferLength +& io.in.valid) min bufSize.U
    else
      bufferLength +& io.in.valid
  
  // This is the amount that the buffer shifts in a cycle
  // NOTE: progression width assumes at least one offset must be zero
  // TODO: use a treeified min-reduction
  val maxProgression =
    ((if(delayForward) 0 else inSize) + bufSize) min outSizes.max
  val progression = Wire(UInt(maxProgression.valBits.W))
  progression := offReady min (if(delayForward) bufferLength else validLength)
  
  buffer
    .tails
    .map(_.take(maxProgression + 1))
    .map(_.padTo(maxProgression + 1, DontCare))
    .map(v => VecInit(v)(progression))
    .zip(buffer.iterator)
    .foreach{case (h, b) => b := h}
  
  for(i <- 0 until inSize)
    when((delayForward.B || i.U +& bufferLength >= progression) &&
        i.U +& bufferLength - progression < bufSize.U) {
      buffer(i.U + bufferLength - progression) := io.in.bits(i)
    }
  
  bufferLength := Mux(delayForward.B || validLength >= progression,
    if(delayBackward)
      validLength - progression // validLength is already min-ed
    else
      (validLength - progression) min bufSize.U,
    0.U)
  
  (io.out zip outSizes zip offsets).zipWithIndex
      .foreach{case (((out, siz), off), i) =>
    val adjBufferLen = bufferLength - off
    val adjValidLen = validLength - off
    
    for(i <- 0 until siz)
      out.bits(i) := Mux(i.U < adjBufferLen, buffer(i.U + off),
        if(delayForward) DontCare else io.in.bits(i.U - adjBufferLen))
    
    if(!singleOut) // offset stays zero with only one output
    off := ((off +& out.ready) min validLength) - progression
    
    val valid = if(delayForward) adjBufferLen else adjValidLen
    when(valid <= siz.U) {
      out.valid := valid
      out.last := (if(delayForward) last else io.in.last)
    } otherwise {
      out.valid := siz.U
      out.last := false.B
    }
    
    // suggest names for some wires
    adjBufferLen.suggestName(s"adjBufferLen_$i")
    adjValidLen.suggestName(s"adjValidLen_$i")
    valid.suggestName(s"valid_$i")  
  }
  
  val readyUnlimit = bufSize.U - bufferLength +&
    (if(delayBackward) 0.U else offReady)
  io.in.ready := inSize.U min readyUnlimit
  last := io.in.last && io.in.valid <= readyUnlimit
}
