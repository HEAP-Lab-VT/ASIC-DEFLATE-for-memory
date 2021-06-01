package treeDepthCounter

import chisel3._
import chisel3.util._
import treePathComparator._
import huffmanParameters._
import inputsAndOutputs._

class treeDepthCounter(params: huffmanParameters) extends Module {
  val io = IO(new Bundle() {
    val start = Input(Bool())
    val inputs = Flipped(new treeGeneratorWrapperOutputs(params))
    val outputs = new treeDepthCounterOutputs(params)
    val finished = Output(Bool())
  })

  // These values collectively ake up the tree that will be used for the depths.
  val leftNode = Reg(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val rightNode = Reg(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val leftNodeIsCharacter = Reg(Vec(params.treeNodes, Bool()))
  val rightNodeIsCharacter = Reg(Vec(params.treeNodes, Bool()))
  val validCharacters = Reg(UInt(params.validCharacterBits.W))
  // This register's value is usedd to know when the module can stop looking for more characters.
  val charactersVisited = Reg(UInt(params.validCharacterBits.W))
  // These registers are used to backtrack through the tree while traversing it.
  val parentNodes = Reg(
    Vec(params.maxPossibleTreeDepth, UInt(params.characterRepresentationBits.W))
  )
  // These registers are used to keep track of current position in the tree and
  // the last explored node of the tree. The position is the sequence of left and
  // right nodes that were followed to get to the current node in the tree, and
  // lastNode is the sequence of left and right nodes followed to get to the last
  // visited node in the tree. positionDepth and lastNodeDepth are the depths of
  // the current position and the last node visited/
  val position = Reg(UInt(params.maxPossibleTreeDepth.W))
  val lastNode = Reg(UInt(params.maxPossibleTreeDepth.W))
  val positionDepth = Reg(UInt(params.maxPossibleTreeDepthBits.W))
  val lastNodeDepth = Reg(UInt(params.maxPossibleTreeDepthBits.W))

  // This is used as part of the decision to take a left or right turn when traversing the tree.
  val tpc = Module(new treePathComparator(params))
  tpc.io.position := position
  tpc.io.lastNode := lastNode
  tpc.io.length := positionDepth

  val characters = Reg(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  )
  val depths = Reg(
    Vec(params.huffmanTreeCharacters, UInt(params.maxPossibleTreeDepthBits.W))
  )

  val numberOfStates = 2
  val waiting :: countingDepth :: Nil = Enum(numberOfStates)

  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)

  switch(state) {
    is(waiting) {
      when(io.start) {
        state := countingDepth
        leftNode := io.inputs.leftNode
        rightNode := io.inputs.rightNode
        leftNodeIsCharacter := io.inputs.leftNodeIsCharacter
        rightNodeIsCharacter := io.inputs.rightNodeIsCharacter
        position := 0.U
        lastNode := 0.U
        positionDepth := 0.U
        lastNodeDepth := 0.U
        validCharacters := io.inputs.validCharacters
        charactersVisited := 0.U
        // The last node added is always the parent node, so by setting the root
        // to the number of the last added node - 1, the parentNode root will always be pointing to
        // the root of the tree.
        parentNodes(0) := io.inputs.validNodes - 1.U
      }
    }

    is(countingDepth) {
      when(charactersVisited >= validCharacters) {
        state := waiting
      }.otherwise {
        when(
          positionDepth >= lastNodeDepth || (positionDepth < lastNodeDepth && !tpc.io.equal)
        ) {
          // In this state, the current position is deeper than the
          // previously explored node on the tree or the paths of this
          // node and the last explored node are different, so explore by
          // travelling down the tree.
          when(leftNodeIsCharacter(parentNodes(positionDepth))) {
            // If the left node is a character, add it to the list of character depths.
            characters(charactersVisited) := leftNode(
              parentNodes(positionDepth)
            )
            depths(charactersVisited) := positionDepth + 1.U
            charactersVisited := charactersVisited + 1.U
            lastNodeDepth := positionDepth + 1.U

            // The last node path needs to be the same as the current position path plus one bit.
            // The left node means the final bit will be a 0, so a bitwise AND is used to mask the
            // bit that needs to be zero.
            lastNode := position & ~(1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth))
            when(rightNodeIsCharacter(parentNodes(positionDepth))) {
              // If the right node is also a character, add it to the list of character depths as
              // well and set it as the most recently visited node.
              characters(charactersVisited + 1.U) := rightNode(
                parentNodes(positionDepth)
              )
              depths(charactersVisited + 1.U) := positionDepth + 1.U
              charactersVisited := charactersVisited + 2.U
              positionDepth := positionDepth - 1.U
              lastNode := position | (1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth))
            }.otherwise {
              // If the right node is not a character, then begin travelling down it to explore.
              positionDepth := positionDepth + 1.U
              position := position | 1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth)
              parentNodes(positionDepth + 1.U) := rightNode(
                parentNodes(positionDepth)
              )
            }
          }.otherwise {
            // If the left node is not a character, explore its children. The right node will be marked
            // as a character while travelling back up the tree later.
            positionDepth := positionDepth + 1.U
            position := position & ~(1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth))
            parentNodes(positionDepth + 1.U) := leftNode(
              parentNodes(positionDepth)
            )
          }
        }.otherwise {
          // If the positionDepth is less than the lastNodeDepth and the
          // paths are equal, this means that the algorithm needs to
          // either travel back up the tree or explore the path to the
          // right, if available.
          when(rightNodeIsCharacter(parentNodes(positionDepth))) {
            positionDepth := positionDepth - 1.U
            lastNode := position | (1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth))
            lastNodeDepth := positionDepth + 1.U
            charactersVisited := charactersVisited + 1.U
            characters(charactersVisited) := rightNode(
              parentNodes(positionDepth)
            )
            depths(charactersVisited) := positionDepth + 1.U
          }.otherwise {
            when(
              (1.U & (position >> ((params.maxPossibleTreeDepth - 1).U - positionDepth))) === true.B
            ) {
              // The right path has already been explored, travel back up the tree.
              positionDepth := positionDepth - 1.U
            }.otherwise {
              positionDepth := positionDepth + 1.U
              position := position | (1.U << ((params.maxPossibleTreeDepth - 1).U - positionDepth))
              parentNodes(positionDepth + 1.U) := rightNode(
                parentNodes(positionDepth)
              )
            }
          }
        }
      }
    }
  }

  io.outputs.characters := characters
  io.outputs.depths := depths
  io.outputs.validCharacters := validCharacters
  io.finished := state === waiting
}

object treeDepthCounter extends App {
  // chisel3.Driver.execute(Array[String](), () => new treeDepthCounter)
}
