package treePathComparator

import chisel3._
import chisel3.util._
import huffmanParameters._

// This module takes in the current position in the tree and the position of the
// last visited node and determines whether their paths are the same so that the
// treeDepthCounter knows how to traverse the tree.
class treePathComparator(param: huffmanParameters) extends Module {
  val io =
    IO(new Bundle {
      // This is the current position in the tree.
      val position = Input(UInt(param.maxPossibleTreeDepth.W))
      // This is the position of the last visited node in the tree.
      val lastNode = Input(UInt(param.maxPossibleTreeDepth.W))
      // This tells the treePathComparator how many bits to count from the root
      // when determining whether the paths are the same or different
      val length = Input(UInt(param.maxPossibleTreeDepthBits.W))
      // This tells whether or not the paths are equal.
      val equal = Output(Bool())
    })
  val xorInputs = Wire(UInt(param.maxPossibleTreeDepth.W))
  // This has an extra bit so the priority encoder can be guaranteed to work by always having at least one bit == 1.
  val reverseXor = Wire(UInt((param.maxPossibleTreeDepth + 1).W))

  // One bits are the bit locations where the position and lastNode differ.
  xorInputs := io.position ^ io.lastNode

  // This reverses the order of the bitwise xor so the built-in priority encoder can be used.
  reverseXor := Cat(true.B, Reverse(xorInputs))

  // The paths are considered equal if the first 1 (meaning a difference in length) comes at or after the length given.
  io.equal := PriorityEncoder(reverseXor) >= io.length
}

object treePathComparator extends App {
  // chisel3.Driver.execute(Array[String](), () => new treePathComparator())
}
