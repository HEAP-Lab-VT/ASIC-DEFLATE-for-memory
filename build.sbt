// See README.md for license details.

def scalacOptionsVersion(scalaVersion: String): Seq[String] = {
  Seq() ++ {
    // If we're building with Scala > 2.11, enable the compile option
    //  switch to support our anonymous Bundle definitions:
    //  https://github.com/scala/bug/issues/10047
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, scalaMajor: Long)) if scalaMajor < 12 => Seq()
      case _ => Seq("-Xsource:2.11")
    }
  }
}

def javacOptionsVersion(scalaVersion: String): Seq[String] = {
  Seq() ++ {
    // Scala 2.12 requires Java 8. We continue to generate
    //  Java 7 compatible code for Scala 2.11
    //  for compatibility with old clients.
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, scalaMajor: Long)) if scalaMajor < 12 =>
        Seq("-source", "1.7", "-target", "1.7")
      case _ =>
        Seq("-source", "1.8", "-target", "1.8")
    }
  }
}

name := "hardware-compressor"

version := "3.2.0"

scalaVersion := "2.12.10"

crossScalaVersions := Seq("2.12.10", "2.11.12")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases")
)

// Provide a managed dependency on X if -DXVersion="" is supplied on the command line.
val defaultVersions = Map(
  "chisel3" -> "3.4.+",
  "chisel-iotesters" -> "1.5.+"
  )

libraryDependencies ++= Seq("chisel3","chisel-iotesters").map {
  dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) }

libraryDependencies += "edu.berkeley.cs" %% "chisel-testers2" % "0.1-SNAPSHOT"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % "test"

scalacOptions ++= scalacOptionsVersion(scalaVersion.value)

javacOptions ++= javacOptionsVersion(scalaVersion.value)

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross
  CrossVersion.full)

// ignore broken glue logic caused by change to lz77 interface
// todo: fix the broken glue logic
unmanagedSources / excludeFilter := HiddenFileFilter ||
  new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
    "src" / "main" / "scala" / "combinations" getCanonicalPath)) ||
  // new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
  //   "src" / "main" / "scala" / "huffman" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
    "src" / "main" / "scala" / "lzw" getCanonicalPath)) ||
  // new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
  //   "src" / "main" / "scala" / "edu" / "vt" / "cs" / "hardware_compressor" /
  //   "huffman" getCanonicalPath)) ||
  // new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
  //   "src" / "main" / "scala" / "edu" / "vt" / "cs" / "hardware_compressor" /
  //   "deflate" getCanonicalPath)) ||
  new SimpleFileFilter(_.getCanonicalPath contains (baseDirectory.value /
    "src" / "main" / "scala" / "huffman" / "buffer" / "cacheLineStitcher.scala"
    getCanonicalPath))

// workaround for sbt bug that causes a hang when killing test execution
Global / cancelable := false
