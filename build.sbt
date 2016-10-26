import org.scalajs.sbtplugin.cross.CrossProject
import sbt.Keys._

val versions = new {
  val circe = "0.5.4"
  val akka = "2.4.11"
  val scalatest = "3.0.0"
  val scala = "2.11.8"
}

lazy val root = project.in(file("."))
  .aggregate(libJS,libJVM)
  .settings(
    scalaVersion := versions.scala,
    publish := {},
    test := Def.sequential(test in libJVM, test in libJS),
    run := {},
    publishLocal := {}
  )

lazy val lib: CrossProject = crossProject.in(file("."))
  .settings(
    bintrayOrganization := Some("flatmap"),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    name := "jsonrpc",
    version := "0.4.0",
    sourceDirectories in Test := Seq.empty,
    scalaVersion := "2.11.8",
    organization := "net.flatmap",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core",
      "io.circe" %%% "circe-generic",
      "io.circe" %%% "circe-parser"
    ).map(_ % versions.circe)
  ).jsSettings(
    test := test in testJS,
    libraryDependencies += "eu.unicredit" %%% "akkajsactorstream" %
      ("0." + versions.akka)
  ).jvmSettings(
    test := test in testJVM,
    libraryDependencies += "com.typesafe.akka" %% "akka-stream" % versions.akka
  )

lazy val libJS = lib.js
lazy val libJVM = lib.jvm

lazy val macroTest = crossProject.in(file("."))
  .settings(
    scalaVersion := "2.11.8",
    target := target.value / "test",
    libraryDependencies += "org.scalatest" %%% "scalatest" %
      versions.scalatest % "test"
  ).dependsOn(lib)

lazy val testJS  = macroTest.js
lazy val testJVM = macroTest.jvm