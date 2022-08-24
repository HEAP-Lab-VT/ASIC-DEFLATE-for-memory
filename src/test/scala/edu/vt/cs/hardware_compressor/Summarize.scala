package edu.vt.cs.hardware_compressor

import java.io.{PrintWriter}
import scala.io.Source

object Summarize extends App {
  
  val out = new PrintWriter(args(0))
  
  case class Summary(
    dumps: String,
    totalSize: Long,
    totalPages: Int, 
    nonzeroSize: Long,
    nonzeroPages: Int,
    compressedSize: Long,
    passedPages: Int,
    failedPages: Int,
    compressorCycles: Long,
    compressorStalls: Long,
    decompressorCycles: Long,
    decompressorStalls: Long
  ) {
    def +(that: Summary): Summary = Summary(
      dumps = this.dumps + " " + that.dumps,
      totalSize = this.totalSize + that.totalSize,
      totalPages = this.totalPages + that.totalPages,
      nonzeroSize = this.nonzeroSize + that.nonzeroSize,
      nonzeroPages = this.nonzeroPages + that.nonzeroPages,
      compressedSize = this.compressedSize + that.compressedSize,
      passedPages = this.passedPages + that.passedPages,
      failedPages = this.failedPages + that.failedPages,
      compressorCycles = this.compressorCycles + that.compressorCycles,
      compressorStalls = this.compressorStalls + that.compressorStalls,
      decompressorCycles = this.decompressorCycles + that.decompressorCycles,
      decompressorStalls = this.decompressorStalls + that.decompressorStalls
    )
  }
  object EmptySummary extends Summary("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  
  val s = args.tail.map{filename =>
    Source.fromFile(filename).getLines()
    .dropWhile(_ != "***** SUMMARY *****")
    .flatMap{l =>
      def wbad = System.err.println(s"warning: bad summary line: '$l'")
      val d = l.split(":").map(_.trim)
      if(d.lengthIs != 2) {
        wbad
        None
      } else d(0) match {
        case
          "dumps" |
          "total (bytes)" |
          "total (pages)" |
          "non-zero (bytes)" |
          "non-zero (pages)" |
          "passed (pages)" |
          "failed (pages)" |
          "pass rate" |
          "compressed (bits)" |
          "compression ratio" |
          "C-cycles" |
          "C-throughput (B/c)" |
          "D-cycles" |
          "D-throughput (B/c)"
          => Some((d(0), d(1)))
        case _ => {
          wbad
          None
        }
      }
    }
    .toMap
  }
  .map(_.map{case (k, v) => (k, Some(v))})
  .map(_.withDefault{k =>
    System.err.println(s"warning: missing key: $k")
    None
  })
  .map{l => Summary(
    dumps = l("dumps").getOrElse(""),
    totalSize = l("total (bytes)").map(_.toLong).getOrElse(0),
    totalPages = l("total (pages)").map(_.toInt).getOrElse(0),
    nonzeroSize = l("non-zero (bytes)").map(_.toLong).getOrElse(0),
    nonzeroPages = l("non-zero (pages)").map(_.toInt).getOrElse(0),
    compressedSize = l("passed (pages)").map(_.toLong).getOrElse(0),
    passedPages = l("failed (pages)").map(_.toInt).getOrElse(0),
    failedPages = l("compressed (bits)").map(_.toInt).getOrElse(0),
    compressorCycles = l("C-cycles").map(_.toLong).getOrElse(0),
    compressorStalls = 0,
    decompressorCycles = l("D-cycles").map(_.toLong).getOrElse(0),
    decompressorStalls = 0
  )}
  .reduce(_ + _)
  
  out.println("***** SUMMARY *****")
  out.println(s"dumps: ${s.dumps}")
  out.println(s"total (bytes): ${s.totalSize}")
  out.println(s"total (pages): ${s.totalPages}")
  out.println(s"non-zero (bytes): ${s.nonzeroSize}")
  out.println(s"non-zero (pages): ${s.nonzeroPages}")
  out.println(s"passed (pages): ${s.passedPages}")
  out.println(s"failed (pages): ${s.failedPages}")
  out.println(s"pass rate: ${s.passedPages.doubleValue / s.nonzeroPages}")
  out.println(s"compressed (bits): ${s.compressedSize}")
  out.println(s"compression ratio: " +
    s"${s.nonzeroSize.doubleValue / s.compressedSize * 8}")
  out.println(s"C-cycles: ${s.compressorCycles}")
  out.println(s"C-throughput (B/c): " +
    s"${s.nonzeroSize.doubleValue / s.compressorCycles}")
  out.println(s"D-cycles: ${s.decompressorCycles}")
  out.println(s"D-throughput (B/c): " +
    s"${s.nonzeroSize.doubleValue / s.decompressorCycles}")
}
