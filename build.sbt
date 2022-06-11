// See README.md for license details.


name := "hardware-compressor"

version := "3.2.0"

scalaVersion := "2.13.8"

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

// Provide a managed dependency on X if -DXVersion="" is supplied on the command line.
val defaultVersions = Map(
  "chisel3" -> "3.5.+"
  )

libraryDependencies ++= Seq("chisel3").map {
  dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) }

addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.5.+" cross CrossVersion.full)


// exclude some unused stuff from compilation
unmanagedSources / excludeFilter := {
  val src = baseDirectory.value / "src" / "main" / "scala"
  val src_hc = baseDirectory.value / "src" / "main" / "scala" / "edu" / "vt" /
    "cs" / "hardware_compressor"
  HiddenFileFilter ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src / "combinations" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src / "lzw" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src / "huffman" / "buffers" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src / "huffman" / "wrappers" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src / "huffman" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains
    (src_hc / "deflate" getCanonicalPath))
}

// workaround for sbt bug that causes a hang when killing test execution
Global / cancelable := false
