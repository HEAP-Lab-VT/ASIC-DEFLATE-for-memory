package edu.vt.cs.hardware_compressor.util

import chisel3._
import chisel3.util._

object WidthOps {
  implicit class WidthOpsBigInt(v: BigInt) {
    def valBits(): Int = log2Ceil(v + 1)
    def idxBits(): Int = log2Ceil(v)
    def valUInt(): UInt = UInt(v.valBits.W)
    def idxUInt(): UInt = UInt(v.idxBits.W)
    def maxVal(): BigInt = (BigInt(1) << v.intValue) - 1
    def space(): BigInt = BigInt(1) << v.intValue
    def isPow2(): Boolean = v == v.ceilPow2
    def ceilPow2(): BigInt = v.idxBits.space
  }
  implicit class WidthOpsInt(v: Int) extends WidthOpsBigInt(v)
  implicit class WidthOpsLong(v: Long) extends WidthOpsBigInt(v)
  implicit class WidthOpsUInt(v: UInt) {
    def maxVal(): BigInt = (1 << v.getWidth) - 1
    def space(): BigInt = 1 << v.getWidth
  }
}
