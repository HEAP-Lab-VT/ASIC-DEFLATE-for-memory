package treeGenerator

import chisel3._
import chisel3.util._
import huffmanParameters._
import inputsAndOutputs._

class treeGenerator(params: huffmanParameters) extends Module {
  val io = IO(new Bundle() {
    val start = Input(Bool())
    val inputs = Flipped(new characterFrequencyModuleOutputs(params.huffmanTreeCharacters, params.inputCharacterBits, params.characterBits))
    val outputs = new treeGeneratorOutputs(params)
    val finished = Output(Bool())
  })

  // characterIn, leftNode, pointerOrCharacter, newPointer, and rightNode are 9 bits instead of 8 bits because the
  // mose significant bit is a flag that shows that the character is actually the
  // escape bit.

  // This is an array of 12-bit registers to store the frequencies of each of the root
  // nodes of the trees that are being generated.
  val frequency = Reg(
    Vec(params.huffmanTreeCharacters, UInt(params.inputCharacterBits.W))
  )

  // This is an array of 8-bit registers that either stores a character
  // (which can be conceptuallized as the root node of its own single-node
  // tree) or a pointer to a node located in the node register array.
  val pointerOrCharacter = Reg(
    Vec(
      params.huffmanTreeCharacters,
      UInt(params.characterRepresentationBits.W)
    )
  )

  // This is an array of 256 1-bit flags that correspond to the 8-bit
  // pointerOrCharacter registers to show whether they are representing a
  // pointer or a character.
  val isCharacter = Reg(Vec(params.huffmanTreeCharacters, Bool()))

  // These registers are used to temporarily store the frequency and pointer
  // to a new node as a place for it to be inserted is located.
  val newFrequency = Reg(UInt(params.inputCharacterBits.W))
  val newPointer = Reg(UInt(params.characterRepresentationBits.W))

  // This is a register used to determine how many valid root nodes there are,
  // starting from 0.
  val validRoots = Reg(UInt(params.validCharacterBits.W))

  // These registers are the node registers. Their names are fairly
  // explanatory, and they are pointed to by the pointerOrCharacter register
  // and themselves. If leftNodeIsCharacter or rightNodeIsCharacter is false,
  // that means it is a pointer to another set of leftNode and rightNode.
  val leftNode = Reg(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val rightNode = Reg(
    Vec(params.treeNodes, UInt(params.characterRepresentationBits.W))
  )
  val leftNodeIsCharacter = Reg(Vec(params.treeNodes, Bool()))
  val rightNodeIsCharacter = Reg(Vec(params.treeNodes, Bool()))

  // These registers will be used to perform the binary search of the root
  // node list.
  val upperNodeIndex = Reg(UInt(params.validCharacterBits.W))
  val lowerNodeIndex = Reg(UInt(params.validCharacterBits.W))

  // This register tells how many of the node registers are valid, starting
  // with a value of 0 meaning that no registers are valid and a value of 6
  // meaning that the registers from register 0 to register 5 are all valid.
  val validNodes = Reg(UInt(params.validTreeNodeBits.W))

  // This register tells how many characters are being put out so the tree depth
  // counter knows when it is done visiting characters.
  val validCharacters = Reg(UInt(params.validCharacterBits.W))

  // This helps to calculate the validRoots and validCharacters starting values
  // by showing how many nonzero  frequencies exist.
  val nonZeroFrequencies = Wire(Vec(params.huffmanTreeCharacters, Bool()))
  for (index <- 0 until params.huffmanTreeCharacters) {
    nonZeroFrequencies(index) := io.inputs.sortedFrequency(index) =/= 0.U
  }

  // These wires can be used in the event that the search is completed. They
  // dramatically reduce the amount of necessary code.
  val searchCompleted = Wire(Bool())
  val matchedIndex = Wire(UInt(params.validCharacterBits.W))
  searchCompleted := false.B
  matchedIndex := 0.U

  val numberOfStates = 3
  val waiting :: createNode :: nodeInsertSearch :: Nil = Enum(numberOfStates)

  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)

  switch(state) {
    is(waiting) {
      when(io.start) {
        state := createNode
        validNodes := 0.U
        frequency := io.inputs.sortedFrequency
        pointerOrCharacter := io.inputs.sortedCharacter
        isCharacter := VecInit(Seq.fill(params.huffmanTreeCharacters)(true.B))
        // These use built-in Chisel hardware to quickly get answers for the validRoots and validCharacters
        validRoots := PopCount(nonZeroFrequencies.asUInt())
        validCharacters := PopCount(nonZeroFrequencies.asUInt())
      }
    }

    is(createNode) {
      when(validRoots < 2.U) {
        when(validNodes === 0.U) {
          // This means that there is only a single character present, so
          // add it to the output.
          validNodes := 1.U
          validCharacters := 1.U
          leftNode(0) := pointerOrCharacter(0)
          leftNodeIsCharacter(0) := 1.U
        }
        state := waiting
      }.otherwise {
        // This state sets the values necessary to create a node
        // and to start the search for the correct place to insert
        // the node.
        state := nodeInsertSearch

        // This adds the newly-created node to the node array.
        validNodes := validNodes + 1.U
        validRoots := validRoots - 1.U
        leftNode(validNodes) := pointerOrCharacter(validRoots - 2.U)
        leftNodeIsCharacter(validNodes) := isCharacter(validRoots - 2.U)
        rightNode(validNodes) := pointerOrCharacter(validRoots - 1.U)
        rightNodeIsCharacter(validNodes) := isCharacter(validRoots - 1.U)

        // Now that the newly-created node has been added to the
        // node array, the new root node needs to be temporarily
        // stored in some registers until it can be safely inserted
        // into the root node array in the correct place to retain
        // the correctly sorted order.
        newFrequency := frequency(validRoots - 2.U) + frequency(
          validRoots - 1.U
        )
        newPointer := validNodes
        upperNodeIndex := 0.U
        lowerNodeIndex := validRoots - 2.U
      }
    }

    is(nodeInsertSearch) {
      // This state performs a binary search, then switches back to createNode.
      when(
        (upperNodeIndex === lowerNodeIndex) || (newFrequency === frequency(
          upperNodeIndex
        ))
      ) {
        searchCompleted := true.B
        matchedIndex := upperNodeIndex
      }.elsewhen(
        (frequency(lowerNodeIndex) === newFrequency) ||
          (((frequency(upperNodeIndex) > newFrequency) && (frequency(
            lowerNodeIndex
          ) < newFrequency)) && ((upperNodeIndex + 1.U) === lowerNodeIndex))
      ) {
        searchCompleted := true.B
        matchedIndex := lowerNodeIndex
      }
      when(searchCompleted) {
        state := createNode
        for (count <- 0 until params.huffmanTreeCharacters) {
          // This loop shifts down all the root nodes at or below the
          // index that the new node will be inserted at.
          when(count.U === matchedIndex) {
            frequency(count) := newFrequency
            pointerOrCharacter(count) := newPointer
            isCharacter(count) := false.B
          }.elsewhen(count.U > matchedIndex) {
            if (count != 0) {
              frequency(count) := frequency(count - 1)
              pointerOrCharacter(count) := pointerOrCharacter(count - 1)
              isCharacter(count) := isCharacter(count - 1)
            }
          }
        }
      }.elsewhen(
          frequency((lowerNodeIndex - upperNodeIndex) / 2.U + upperNodeIndex) < newFrequency
        ) {
          lowerNodeIndex := (lowerNodeIndex - upperNodeIndex) / 2.U + upperNodeIndex
        }
        .otherwise {
          upperNodeIndex := (lowerNodeIndex - upperNodeIndex) / 2.U + upperNodeIndex
        }
    }
  }

  io.outputs.leftNode := leftNode
  io.outputs.rightNode := rightNode
  io.outputs.leftNodeIsCharacter := leftNodeIsCharacter
  io.outputs.rightNodeIsCharacter := rightNodeIsCharacter
  io.outputs.validNodes := validNodes
  io.outputs.validCharacters := validCharacters
  io.finished := state === waiting
}

object treeGenerator extends App {
  // chisel3.Driver.execute(Array[String](), () => new treeGenerator)
}
