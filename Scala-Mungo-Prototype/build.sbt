
name := "Scala-Mungo-Prototype"
scalaVersion := "2.13.3"

description := "Protocol checker for Scala"

ThisBuild / organization := "org.me"

ThisBuild / version := "1.8"
//ThisBuild / version := "0.12.2-SNAPSHOT"


licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

homepage := Some(url("https://github.com/Aliceravier/Scala-Mungo"))

libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value

libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % Test




