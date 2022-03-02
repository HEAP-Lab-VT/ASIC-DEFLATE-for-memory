package edu.vt.cs.hardware_compressor.util

import chisel3._
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
 * accepts chunked or lookahead data since this module will act as a buffer to
 * facilitate such a consumer
 */
class UniversalConnector[T <: Data](inSize: Int, outSize: Int, gen: T)
    extends StreamBuffer[T](inSize, outSize, outSize, gen, false) {}

object UniversalConnector {
  def apply[T <: Data](
      inSize: Int = 0,
      outSize: Int = 0,
      gen: T = new Bundle {}):
      UniversalConnector[T] =
    new UniversalConnector(inSize, outSize, gen)
}


class StreamBundle[I <: Data, O <: Data](inC: Int, inGen: I, outC: Int, outGen: O) extends Bundle {
  val in = Flipped(DecoupledStream(inC, inGen))
  val out = DecoupledStream(outC, outGen)
  
  override def cloneType: this.type =
    new StreamBundle(inC, inGen, outC, outGen).asInstanceOf[this.type]
}


class StreamBuffer[T <: Data](inSize: Int, outSize: Int, bufSize: Int, gen: T,
    delay: Boolean) extends Module {
  val io = IO(new StreamBundle(inSize, gen, outSize, gen))
  
  val buffer = Reg(Vec(bufSize, gen))
  val bufferLength = RegInit(0.U(bufSize.valBits.W))
  
  for(i <- 0 until bufSize)
    if(delay)
      buffer(i) := buffer(i.U + io.out.ready)
    else when(i.U +& io.out.ready < bufferLength) {
      buffer(i) := buffer(i.U + io.out.ready)
    } otherwise {
      buffer(i) := io.in.bits(i.U + io.out.ready - bufferLength)
    }
  
  bufferLength := Mux(bufferLength +& io.in.valid > io.out.ready,
    (bufferLength +& io.in.valid - io.out.ready) min bufSize.U, 0.U)
  
  for(i <- 0 until outSize)
    io.out.bits(i) := (if(delay) buffer(i) else
      Mux(i.U < bufferLength, buffer(i), io.in.bits(i.U - bufferLength)))
  
  val outUnbound = if(delay) bufferLength else (bufferLength +& io.in.valid)
  io.out.valid := outUnbound min outSize.U
  io.in.ready := (bufSize.U - bufferLength) min inSize.U
  io.out.last := io.in.last && outUnbound <= outSize.U
}


class StreamTee[T <: Data](inSize: Int, outSizes: Seq[Int], bufSize: Int,
    gen: T, delay: Boolean = false) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(inSize, gen))
    val out = MixedVec(outSizes.map(s => DecoupledStream(s, gen)))
  })
  
  val buffer = Reg(Vec(bufSize, gen))
  val bufferLength = RegInit(0.U(bufSize.valBits.W))
  val offsets = Seq.fill(outSizes.length)(RegInit(0.U(bufSize.valBits.W)))
  
  // This is the amount that the buffer shifts in a cycle
  // NOTE: progression width assumes at least one offset must be zero
  // TODO: use a treeified min-reduction
  val progression = Wire(UInt(
    (((if(delay) 0 else inSize) + bufSize) min outSizes.max).valBits.W))
  progression := (if(delay) bufferLength else (bufferLength +& io.in.valid)) min
    io.out.zip(offsets).map(o => o._1.ready +& o._2).reduce(_ min _)
  
  for(i <- 0 until bufSize)
    when(i.U +& progression < bufferLength) {
      buffer(i) := buffer(i.U + progression)
    } otherwise {
      buffer(i) := io.in.bits(i.U + progression - bufferLength)
    }
  
  bufferLength := Mux(bufferLength +& io.in.valid >= progression,
    (bufferLength +& io.in.valid - progression) min bufSize.U, 0.U)
  
  io.out.zip(outSizes).zip(offsets).foreach{case ((out, siz), off) =>
    val adjBufLen = bufferLength - off;
    
    for(i <- 0 until siz)
      out.bits(i) := Mux(i.U < adjBufLen, buffer(i.U + off),
        if(delay) io.in.bits(i.U - adjBufLen) else DontCare)
    
    off := ((off +& out.ready) min (bufferLength +& io.in.valid)) - progression
    
    val validUnbound = if(delay) adjBufLen else (adjBufLen +& io.in.valid)
    when(validUnbound <= siz.U) {
      out.valid := validUnbound
      out.last := io.in.last
    } otherwise {
      out.valid := siz.U
      out.last := false.B
    }
  }
  
  io.in.ready := (bufSize.U - bufferLength) min inSize.U
}


class SimpleStreamTee[T <: Data](inSize: Int, outSizes: Seq[Int], bufSize: Int,
    gen: T, delay: Boolean = false) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledStream(inSize, gen))
    val out = MixedVec(outSizes.map(s => DecoupledStream(s, gen)))
  })
  
  val buffer = Reg(Vec(bufSize, gen))
  val bufferLength = RegInit(0.U(bufSize.valBits.W))
  val offsets = Seq.fill(outSizes.length)(RegInit(0.U(bufSize.valBits.W)))
  
  // This is the amount that the buffer shifts in a cycle
  // NOTE: progression width assumes at least one offset must be zero
  // TODO: use a treeified min-reduction
  val progression = 0.U
  
  for(i <- 0 until bufSize)
    when(i.U +& progression < bufferLength) {
      buffer(i) := buffer(i.U + progression)
    } otherwise {
      buffer(i) := io.in.bits(i.U + progression - bufferLength)
    }
  
  bufferLength := Mux(bufferLength +& io.in.valid >= progression,
    (bufferLength +& io.in.valid - progression) min bufSize.U, 0.U)
  
  io.out.zip(outSizes).zip(offsets).foreach{case ((out, siz), off) =>
    val adjBufLen = bufferLength - off;
    
    for(i <- 0 until siz)
      out.bits(i) := Mux(i.U < adjBufLen, buffer(i.U + off),
        if(delay) io.in.bits(i.U - adjBufLen) else DontCare)
    
    off := ((off +& out.ready) min (bufferLength +& io.in.valid)) - progression
    
    val validUnbound = if(delay) adjBufLen else (adjBufLen +& io.in.valid)
    when(validUnbound <= siz.U) {
      out.valid := validUnbound
      out.last := io.in.last
    } otherwise {
      out.valid := siz.U
      out.last := false.B
    }
  }
  
  io.in.ready := (bufSize.U - bufferLength) min inSize.U
}
