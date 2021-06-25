package lz77

import lz77Parameters._
import scala.util.control.Breaks._

object LZ77Golden {
  def encode(address: Int, length: Int, params: lz77Parameters): Seq[Int] = {
    
    var encoding : BigInt = 0
    encoding <<= params.characterBits
    encoding |= params.escapeCharacter
    encoding <<= 1
    encoding |= ~params.escapeCharacter >> (params.characterBits - 1) & 1
    encoding <<= params.camAddressBits
    encoding |= address
    
    encoding <<= params.minEncodingSequenceLengthBits
    if(length <= params.maxCharactersInMinEncoding) {
      encoding |= length - params.minCharactersToEncode
    }
    else {
      encoding |= (1 << params.minEncodingSequenceLengthBits) - 1
      var remaining = length - params.maxCharactersInMinEncoding - 1
      while(remaining >= 0) {
        encoding <<= params.characterBits
        encoding |= params.maxCharacterValue min remaining
        remaining -= params.extraCharacterLengthIncrease
      }
    }
    
    val encodinglength =
      if(length <= params.maxCharactersInMinEncoding)
        params.minEncodingWidth / params.characterBits
      else
        (params.minEncodingWidth / params.characterBits) + 1 +
          ((length - params.maxCharactersInMinEncoding) /
            params.maxCharacterValue)
    
    return (0 until encodinglength reverse).map(i =>
      (encoding >> (i * params.characterBits) & params.maxCharacterValue).toInt)
  }
  
  def compress(data: Seq[Int], params: lz77Parameters): Seq[Int] = {
    var compressed = Seq.empty[Int]
    var cam = Seq.empty[Int]
    var skip = 0
    
    for(curData <- data.tails) {
      // find match
      val matched = cam
        .tails
        .drop(1)
        .map(_ ++ curData)
        .map(_.zip(curData))
        .map(_.takeWhile(e => e._1 == e._2))
        .map(_.length)
        .zipWithIndex
        .fold((0, 0))((m1, m2) => if(m1._1 > m2._1) m1 else m2)
      
      // add to compressed characters
      if(skip > 0) {
        skip -= 1
      }
      else if(matched._1 >= params.minCharactersToEncode) {
        compressed ++= encode(matched._2, matched._1, params)
        skip = matched._1 - 1
      }
      else {
        compressed :+= curData(0)
      }
      
      // update cam
      cam :+= curData(0)
      if(cam.length > params.camCharacters)
        cam = cam.tail
    }
    
    return compressed
  }
  
  def generateData(
      len: Int = 4096,
      params: lz77Parameters,
      overlap: Boolean = true
  ): Tuple2[Seq[Int], Seq[Int]] = {
    var uncompressed = Seq.empty[Int]
    var compressed = uncompressed.flatMap(d =>
      if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    while(uncompressed.length < len) breakable {
      if(scala.math.random() >= .1 ||
          uncompressed.length + params.minCharactersToEncode >= len ||
          uncompressed.length == 0) {
        val char = (scala.math.random() * 256).toInt
        uncompressed :+= char
        compressed :+= char
        if(char == params.escapeCharacter)
          compressed :+= char
      }
      else {
        val index = params.camCharacters - 1 - (scala.math.random() *
          (uncompressed.length min params.camCharacters)).toInt
        var length = params.minCharactersToEncode
        val p = scala.math.random() * .3 + .7
        while(scala.math.random() < p && length < len - uncompressed.length)
          length += 1
        if(index + length > params.camCharacters && !overlap) break // continue
        
        compressed ++= encode(index, length, params)
        
        uncompressed ++= Iterator.continually(
          (Seq.fill(params.camCharacters)(0) ++ uncompressed)
          .takeRight(params.camCharacters)
          .drop(index))
          .flatten
          .take(length)
      }
    }
    
    (uncompressed, compressed)
  }
}
