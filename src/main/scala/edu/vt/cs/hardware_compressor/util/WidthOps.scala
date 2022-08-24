package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.util._
import chisel3.internal.firrtl.Width

import scala.language.implicitConversions

object WidthOps {
  implicit class WidthOps_BigInt(v: BigInt) {
    def valBits: Int = log2Ceil(v + 1)
    def idxBits: Int = log2Ceil(v)
    def valUInt: UInt = UInt(v.valBits.W)
    def idxUInt: UInt = UInt(v.idxBits.W)
    def maxVal: BigInt = (BigInt(1) << v.intValue) - 1
    def space: BigInt = BigInt(1) << v.intValue
    def isPow2: Boolean = v.bitCount == 1
    def ceilPow2: BigInt = v.idxBits.space
  }
  implicit class WidthOps_Int(v: Int) extends WidthOps_BigInt(v)
  implicit class WidthOps_Long(v: Long) extends WidthOps_BigInt(v)
  
  implicit class WidthOps_UInt(v: UInt) {
    def maxVal: BigInt = (1 << v.getWidth) - 1
    def space: BigInt = 1 << v.getWidth
    def asUInt(width: Width): UInt = v.pad(width.get)(width.get - 1, 0)
  }
}
