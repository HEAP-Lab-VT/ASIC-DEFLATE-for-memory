package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.util._
import edu.vt.cs.hardware_compressor.util.WidthOps._

object ArithmeticOps {
  implicit class ArithmeticOpsUInt(v: UInt) {
    def div(divisor: BigInt): Tuple2[UInt, UInt] =
      if(divisor.isPow2) {
        val shamt = divisor.idxBits
        (
          v >> shamt,
          v(shamt - 1, 0)
        )
      } else {
        System.out.println("Warning: generating hardware divider for divisor " +
          s"${divisor}")
        (
          v / divisor.U,
          v % divisor.U
        )
      }
    def mul(multiplicand: BigInt): UInt = {
      // consider the power of two's: 1, 2, 4, 8, 16, ...
      // I must find a sequence of s_i in {1, 0, -1} such that
      // sum{from i = 0}(s_i * i^2) = multiplicand and
      // s_i = 0 for as many i's as possible
      // 
      // 00010000 -> s_4 = 1
      // 11111111 -> s_8 = 1, s_0 = -1
      // 11101111 -> s_8 = 1, s_4 = -1, s_0 = -1
      // 11110000 -> s_8 = 1, s_4 = -1
      
      // val bits = Iterator.iterate(multiplicand << 1)(_ >> 1)
      //   .takeWhile(if(multiplicand >= 0) m => m != 0 else m => m != -1)
      //   .map((_ & 1) != 0)
      //   .concat(multiplicand < 0)
      //   .sliding(2)
      //   .
      // 
      // 
      //   .zip(Iterator.from(0).map(v << _))
      //   .filter(_._1)
      //   .map(_._2)
      //   .reduce(_ +& _)
      
      Iterator.iterate(multiplicand)(_ >> 1)
        .takeWhile(_ != 0)
        .map(_ & 1)
        .map(_ != 0)
        .zip(Iterator.from(0).map(v << _))
        .filter(_._1)
        .map(_._2)
        .reduceOption(_ +& _)
        .getOrElse(0.U)
    }
  }
  implicit class ArithmeticOpsSInt(v: SInt) {
    // def div(base: BigInt): Tuple2[UInt, UInt] =
    //   if(base.isPow2) {
    //     val shamt = base.idxBits
    //     (
    //       v >> shamt,
    //       v(shamt - 1, 0)
    //     )
    //   } else {
    //     System.out.println("Warning: generating hardware divider for divisor " +
    //       s"${base}")
    //     (
    //       v / base.S,
    //       v % base.S
    //     )
    //   }
  }
}
