// See README.md for license details.

package treeNormalizer

import chisel3._
import chisel3.util._
import inputsAndOutputs._

class treeNormalizer(
    characterBits: Int = 8,
    sortMuxBits: Int = 12, // 2 to this power is the number of input characters.
    treeCharacters: Int = 32,
    treeDepthBits: Int = 8,
    inputRegister: Boolean = false, // This tells whether or not to use registers to hold the values given on the input after start
    treeDesiredMaxDepth: Int = 15
) extends Module {
  val validNodeBits = characterBits + 1
  // character bits is the number of bits in a character of the data, but characterInbits has an extra bit so it can also represent the escape character
  val characterInBits = characterBits + 1
  val treeMaxDepth = treeCharacters

  val io = IO(new Bundle {
    val start = Input(Bool())
    val inputs = Flipped(new sortOutputs(treeCharacters, treeDepthBits, characterInBits))
    val outputs = new treeNormalizerOutputs(
      validNodeBits,
      treeCharacters,
      characterInBits,
      treeDepthBits
    )
    val finished = Output(Bool())
  })

  val numberOfStates = 3
  val waiting :: travellingUp :: travellingDown :: Nil = Enum(numberOfStates)

  val state = RegInit(UInt(log2Ceil(numberOfStates).W), waiting)

  val depthsOut = RegInit(VecInit(Seq.fill(treeCharacters)(0.U(treeDepthBits.W))))
  val validNodesIn = if (inputRegister) {
    Reg(UInt(validNodeBits.W))
  } else {
    Wire(UInt(validNodeBits.W))
  }
  val charactersIn = if (inputRegister) {
    Reg(Vec(treeCharacters, UInt(characterInBits.W)))
  } else {
    Wire(Vec(treeCharacters, UInt(characterInBits.W)))
  }
  val depthsIn = if (inputRegister) {
    Reg(Vec(treeCharacters, UInt(treeDepthBits.W)))
  } else {
    Wire(Vec(treeCharacters, UInt(treeDepthBits.W)))
  }

  if (inputRegister == false) {
    // If there are no input registers, the wires need to be set equal to the io inputs
    validNodesIn := io.inputs.itemNumber
    charactersIn := io.inputs.outputTags
    depthsIn := io.inputs.outputData
  }

  // The number of characters at each given depth. Used to calculate whether characters need
  // to be moved to a deeper depth
  val charactersAtDepth = Reg(
    Vec(treeDesiredMaxDepth, SInt((log2Ceil(treeMaxDepth + 1) + 1).W))
  )

  val freeCharactersBits = treeMaxDepth + 1
  // This is used in the calculation of the number of free characters remaining. The idea is that, the shallower the entry in the character tree, the more of the encoding space is
  // used up by that entry. For instance, using a single-bit encoding for a character means every other character needs to be two bits or more, and removes half of the potential
  // encoding possibilities
  val subtractCharacters = Wire(
    Vec(treeDesiredMaxDepth, SInt(freeCharactersBits.W))
  )

  // This is positive or 0 if there is plenty of space for all characters in the given number of depths, but
  // negative if some characters need to have their depths increased
  val freeCharacters = Wire(SInt(freeCharactersBits.W))
  // fold left sums all the elements in the vec
  subtractCharacters(0) := charactersAtDepth(0) << (treeDesiredMaxDepth - 1)
  for (index <- 1 until treeDesiredMaxDepth) {
    subtractCharacters(index) := subtractCharacters(index - 1) + (charactersAtDepth(index) << (treeDesiredMaxDepth - 1 - index))
  }
  freeCharacters := (1.S << treeDesiredMaxDepth) - subtractCharacters(treeDesiredMaxDepth - 1)

  val iteration = Reg(UInt((log2Ceil(treeCharacters) + 1).W))

  switch(state) {
    is(waiting) {
      when(io.start) {
        state := travellingDown
        charactersAtDepth := VecInit(
          Seq.fill(treeDesiredMaxDepth)(0.S((log2Ceil(treeMaxDepth) + 1).W))
        )
        iteration := 0.U
        if (inputRegister) {
          validNodesIn := io.inputs.itemNumber
          charactersIn := io.inputs.outputTags
          depthsIn := io.inputs.outputData
        }
      }
    }
    is(travellingDown) {
      when(depthsIn(iteration) > treeDesiredMaxDepth.U) {
        charactersAtDepth(treeDesiredMaxDepth - 1) := charactersAtDepth(
          treeDesiredMaxDepth - 1
        ) + 1.S
      }.otherwise {
        charactersAtDepth(depthsIn(iteration) - 1.U) := charactersAtDepth(depthsIn(iteration) - 1.U) + 1.S
      }

      iteration := iteration + 1.U
      when(
        (iteration + 1.U >= validNodesIn) || (iteration + 1.U >= treeCharacters.U)
      ) {
        iteration := iteration
        state := travellingUp
      }
    }
    is(travellingUp) {
      when(depthsIn(iteration) > treeDesiredMaxDepth.U) {
        // If the depth is lower than the desired maximum depth, add it to the maximum desired depth in the output instead.
        // No need to mark it in charactersAtDepth, it was counted in the last state of the state machine.
        depthsOut(iteration) := treeDesiredMaxDepth.U
      }.otherwise {
        when(freeCharacters >= 0.S) {
          // If there are characters remaining in the encoding space still, the output depth can be the same as the input depth.
          depthsOut(iteration) := depthsIn(iteration)
        }.otherwise {
          // If there is a deficit of encodings in the encoding space, drop the current character to a deeper space until there is room
          // or it reaches the max depth.
          when(depthsIn(iteration) < treeDesiredMaxDepth.U) {
            // This array represents how many spaces at the lowest depth of the encoding space would be added if the current
            // character were moved to that space instead of the current one. The first element, at index 0, represents the character
            // being moved to be at depth 1 in the tree.
            val lowestDepth = Wire(Vec(treeDesiredMaxDepth, SInt((log2Ceil(treeDesiredMaxDepth + 1) + 1).W)))
            for (index <- 0 until treeDesiredMaxDepth) {
              // zext adds a zero to zero extend the UInt, then we can safely convert to SInt
              lowestDepth(index) := (1.S << (treeDesiredMaxDepth.U - depthsIn(iteration))) - (1.S << (treeDesiredMaxDepth - index - 1))
            }
            // This array represents which of the options, if any, would result in a non-negative freeCharacters value.
            val depthsGivingNonNegativeFreeCharacters = Wire(Vec(treeDesiredMaxDepth, Bool()))
            depthsGivingNonNegativeFreeCharacters := lowestDepth.map(lowestDepthValue => { (freeCharacters + lowestDepthValue) >= 0.S })
            when(depthsGivingNonNegativeFreeCharacters.reduce(_ || _)(false.B)) {
              // None of the depths the character could be moved to results in a non-negative freeCharacters,
              // so we will move it to the max depth and move on.
              depthsOut(iteration) := treeDesiredMaxDepth.U
              charactersAtDepth(depthsIn(iteration) - 1.U) := charactersAtDepth(depthsIn(iteration) - 1.U) - 1.S
              charactersAtDepth(treeDesiredMaxDepth - 1) := charactersAtDepth(treeDesiredMaxDepth - 1) + 1.S
            }.otherwise {
              // There exists a depth this character could be moved to that would result in a non-negative freeCharacters, so move to that depth.
              val newDepth = depthsGivingNonNegativeFreeCharacters.indexWhere((boolValue: Bool) => boolValue === true.B) +& 1.U
              depthsOut(iteration) := newDepth
              charactersAtDepth(depthsIn(iteration) - 1.U) := charactersAtDepth(depthsIn(iteration) - 1.U) - 1.S
              charactersAtDepth(newDepth - 1.U) := charactersAtDepth(newDepth - 1.U) + 1.S
            }
          }.otherwise {
            // The character is already at treeDesiredMaxDepth depth, so nothing can be done to free up more encoding space.
            depthsOut(iteration) := depthsIn(iteration)
          }
        }
      }

      iteration := iteration - 1.U
      when(iteration === 0.U) {
        state := waiting
      }
    }
  }

  io.outputs.charactersOut := charactersIn
  io.outputs.depthsOut := depthsOut
  io.outputs.validNodesOut := validNodesIn
  io.finished := state === waiting
}

object treeNormalizer extends App {
  chisel3.Driver.execute(Array[String](), () => new treeNormalizer)
}
