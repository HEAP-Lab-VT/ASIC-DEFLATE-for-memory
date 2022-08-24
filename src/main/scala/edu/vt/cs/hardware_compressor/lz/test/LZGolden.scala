package edu.vt.cs.hardware_compressor.lz.test

import edu.vt.cs.hardware_compressor.lz.Parameters
import edu.vt.cs.hardware_compressor.util.WidthOps._
import scala.util.control.Breaks._
import java.io._
import java.nio.file.Path

// Note: This can be compiled into a jar by using the following sbt command:
// set assembly / mainClass :=
// Some("edu.vt.cs.hardware_compressor.lz.test.LZGoldenCompress"); assembly

object LZGolden {
  def encode(address: Int, length: Int, params: Parameters): Seq[Int] = {
    
    var encoding : BigInt = 0
    encoding <<= params.characterBits
    encoding |= params.escapeCharacter
    encoding <<= 1
    encoding |= ~params.escapeCharacter >> (params.characterBits - 1) & 1
    encoding <<= params.camSize.idxBits
    encoding |= address
    
    encoding <<= params.minEncodingLengthBits
    if(length <= params.maxCharsInMinEncoding) {
      encoding |= length - params.minCharsToEncode
    }
    else {
      encoding |= (1 << params.minEncodingLengthBits) - 1
      var remaining = length - params.maxCharsInMinEncoding - 1
      while(remaining >= 0) {
        encoding <<= params.characterBits
        encoding |= params.characterBits.maxVal min remaining
        remaining -= params.extraCharacterLengthIncrease
      }
    }
    
    val encodinglength =
      if(length <= params.maxCharsInMinEncoding)
        params.minEncodingChars
      else
        params.minEncodingChars + 1 +
          ((length - params.maxCharsInMinEncoding - 1) /
            params.characterBits.maxVal.intValue)
    
    return (0 until encodinglength).reverse.map(i =>
      (encoding >> (i * params.characterBits) &
        params.characterBits.maxVal).toInt)
  }
  
  def compress(data: Seq[Int], params: Parameters): Seq[Int] =
    LazyList.iterate((LazyList.from(data), Seq.empty[Int], Seq.empty[Int]))
    {case (data, cam, _) =>
      if(data.isEmpty) (LazyList.empty[Int], Seq.empty[Int], Seq.empty[Int])
      else (if(cam.isEmpty) None else Some(cam))
        .map(_
          .tails.toSeq.init
          .map{_ ++: data}
          .map(_.zip(data))
          .map(_.take(params.maxCharsToEncode))
          .map(_.takeWhile(e => e._1 == e._2))
          .map(_.length)
          .zipWithIndex
          .reverse
          .maxBy(_._1))
        .filter(_._1 >= params.minCharsToEncode)
        .map{case (l, i) => (l, i, data.splitAt(l))}
        .map{case (l, i, d) => (
          d._2,
          (cam ++: d._1).takeRight(params.camSize),
          encode(i - cam.length + params.camSize, l, params))}
        .getOrElse((
          data.tail,
          (cam :+ data.head).takeRight(params.camSize),
          if(data.head == params.escapeCharacter) Seq(data.head, data.head)
          else Seq(data.head)))
    }
    .drop(1)
    .takeWhile(_._3.nonEmpty)
    .flatMap(_._3)
  
  def generateData(
      len: Int = 4096,
      params: Parameters,
      overlap: Boolean = true
  ): Tuple2[Seq[Int], Seq[Int]] = {
    var uncompressed = Seq.empty[Int]
    var compressed = uncompressed.flatMap(d =>
      if(d == params.escapeCharacter) Seq(d, d) else Seq(d))
    while(uncompressed.length < len) breakable {
      if(scala.math.random() >= .1 ||
          uncompressed.length + params.minCharsToEncode >= len ||
          uncompressed.length == 0) {
        val char = (scala.math.random() * 256).toInt
        uncompressed :+= char
        compressed :+= char
        if(char == params.escapeCharacter)
          compressed :+= char
      }
      else {
        val index = params.camSize - 1 - (scala.math.random() *
          (uncompressed.length min params.camSize)).toInt
        var length = params.minCharsToEncode
        val p = scala.math.random() * .3 + .7
        while(scala.math.random() < p && length < len - uncompressed.length)
          length += 1
        if(index + length > params.camSize && !overlap) break() // continue
        
        compressed ++= encode(index, length, params)
        
        uncompressed ++= Iterator.continually(
          (Seq.fill(params.camSize)(0) ++ uncompressed)
          .takeRight(params.camSize)
          .drop(index))
          .flatten
          .take(length)
      }
    }
    
    (uncompressed, compressed)
  }
}


object LZGoldenCompress extends App {
  val params = Parameters.fromCSV(Path.of("configFiles/lz.csv"))
  val in =
    if(args.length >= 1 && args(0) != "-")
      new BufferedInputStream(new FileInputStream(args(0)))
    else
      System.in
  val out =
    if(args.length >= 2 && args(1) != "-")
      new BufferedOutputStream(new FileOutputStream(args(1)))
    else
      System.out
  try {
    LZGolden.compress(LazyList.continually(in.read).takeWhile(_ != -1), params)
      .foreach(b => out.write(b))
    out.flush
  } finally {
    if(in != System.in) in.close
    if(out != System.out) out.close
  }
}
