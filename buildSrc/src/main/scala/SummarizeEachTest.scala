
import java.io.{PrintWriter}
import java.nio.file.Files
import org.gradle.api._
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import scala.io.Source
import scala.util.Using

abstract class SummarizeEachTest extends DefaultTask {
  
  @InputDirectory
  def getReportDir: DirectoryProperty
  @OutputDirectory
  def getSummaryDir: DirectoryProperty
  
  @TaskAction
  def doSummarize: Unit = {
    getProject().delete(getSummaryDir)
    getProject().mkdir(getSummaryDir)
    // TODO: issue warning when File.listFiles returs null
    getReportDir.getAsFile.get.listFiles(_.isDirectory).foreach { bench =>
      val benchSummary = bench.listFiles
      .filter(!_.getName().endsWith(".vcd"))
      .map { reportFile =>
        Using(Source.fromFile(reportFile)) { source => Some(source.getLines())
          .map(_.dropWhile(_ != "***** SUMMARY *****").splitAt(1))
          .flatMap(s =>
            if(s._1.exists(_ == "***** SUMMARY *****"))
              Some(s._2)
            else {
              System.err.println(s"warning: $reportFile: no summary section");
              None
            }
          )
          .map(_
            .flatMap{l =>
              def wbad = System.err.println(s"warning: $reportFile: " +
                s"bad summary line: '$l'")
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
            .map{case (k, v) => (k, Some(v))}
            .withDefault{k =>
              System.err.println(s"warning: $reportFile: missing key: $k")
              None
            }
          )
        }
        .recover { ex =>
          System.err.println(s"Warning: $reportFile: " +
            "An error occured while reading the report file.")
          ex.printStackTrace()
          None
        }
        .get
        .map{l => Summary(
          dumps = l("dumps").map(_.split("\\s*,\\s*").toSet)
            .getOrElse(Set.empty),
          totalSize = l("total (bytes)").map(_.toLong).getOrElse(0),
          totalPages = l("total (pages)").map(_.toInt).getOrElse(0),
          nonzeroSize = l("non-zero (bytes)").map(_.toLong).getOrElse(0),
          nonzeroPages = l("non-zero (pages)").map(_.toInt).getOrElse(0),
          passedPages = l("passed (pages)").map(_.toInt).getOrElse(0),
          failedPages = l("failed (pages)").map(_.toInt).getOrElse(0),
          compressedSize = l("compressed (bits)").map(_.toLong).getOrElse(0),
          compressorCycles = l("C-cycles").map(_.toLong).getOrElse(0),
          compressorStalls = 0,
          decompressorCycles = l("D-cycles").map(_.toLong).getOrElse(0),
          decompressorStalls = 0
        )}
      }
      .flatten
      .fold(Summary.empty)(_ + _)
      
      Using(
        new PrintWriter(getSummaryDir.file(bench.getName).get.getAsFile)
      ) { out =>
        benchSummary.print(out)
      }
    }
  }
}

private case class Summary(
  dumps: Set[String],
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
    dumps = this.dumps ++ that.dumps,
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
  
  def print(sink: PrintWriter): Unit = {
    sink.println("***** SUMMARY *****")
    sink.println(s"dumps: ${this.dumps.mkString(",")}")
    sink.println(s"total (bytes): ${this.totalSize}")
    sink.println(s"total (pages): ${this.totalPages}")
    sink.println(s"non-zero (bytes): ${this.nonzeroSize}")
    sink.println(s"non-zero (pages): ${this.nonzeroPages}")
    sink.println(s"passed (pages): ${this.passedPages}")
    sink.println(s"failed (pages): ${this.failedPages}")
    sink.println(s"pass rate: " +
      s"${this.passedPages.doubleValue / this.nonzeroPages}")
    sink.println(s"compressed (bits): ${this.compressedSize}")
    sink.println(s"compression ratio: " +
      s"${this.nonzeroSize.doubleValue / this.compressedSize * 8}")
    sink.println(s"C-cycles: ${this.compressorCycles}")
    sink.println(s"C-throughput (B/c): " +
      s"${this.nonzeroSize.doubleValue / this.compressorCycles}")
    sink.println(s"D-cycles: ${this.decompressorCycles}")
    sink.println(s"D-throughput (B/c): " +
      s"${this.nonzeroSize.doubleValue / this.decompressorCycles}")
  }
}
private object Summary {
  object empty extends Summary(Set.empty, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
}
