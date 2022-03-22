package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import chisel3.tester._
// import scala.util.control.Breaks._

class StreamComparisonTester[T <: Module {def io : StreamBundle[UInt, UInt]}](
    module: T,
    input: Seq[Int],
    expected: Seq[Int])
    extends PeekPokeTester[T](module) {
  
  // initialize inputs
  for(index <- 0 until module.io.in.bits.length)
    poke(module.io.in.bits(index), 0)
  poke(module.io.in.valid, 0)
  poke(module.io.out.ready, 0)
  poke(module.io.in.finished, false)
  
  var inidx = 0
  var outidx = 0
  
  var timeout = 10000
  while(peek(module.io.out.finished) == 0 && timeout > 0) {
    poke(module.io.in.valid, 0)
    poke(module.io.out.ready, 0)
    poke(module.io.in.finished, true)
    for(i <- 0 until
        (module.io.in.bits.length min (input.length - inidx))) {
      poke(module.io.in.bits(i), input(inidx + i))
      poke(module.io.in.valid, i + 1)
      poke(module.io.in.finished, false)
    }
    
    poke(module.io.out.ready, module.io.out.bits.length)
    
    // println(s"out valid = ${peek(lz.io.out.valid)}; in ready = ${peek(lz.io.in.ready)}")
    
    inidx += (peek(module.io.in.ready) min (peek(module.io.in.valid))).intValue
    
    for(i <- 0 until
        (peek(module.io.out.ready) min (peek(module.io.out.valid))).intValue) {
      if(outidx + i < expected.length)
        expect(module.io.out.bits(i), expected(outidx + i))
      else {
        println(s"${i}: ${peek(module.io.out.bits(i))} (indexed past end of data: ${outidx + i})")
        fail
      }
    }
    
    outidx += (peek(module.io.out.ready) min (peek(module.io.out.valid))).intValue
    
    step(1)
    timeout -= 1
  }
  
  if(outidx != expected.length) {
    println(s"outidx was ${outidx}; Expected ${expected.length}")
    fail
  }
}
