package edu.vt.cs.hardware_compressor.util

import chisel3._

import scala.language.implicitConversions

object StrongWhenOps {
  implicit class StrongWhenOps_Data(data: Data) {
    def :@=(that: => Data): Unit = when(when.cond){data.:=(that)}
  }
}
