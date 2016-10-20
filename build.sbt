import org.scalajs.sbtplugin.cross.CrossProject
import sbt.Keys._

val circeVersion = "0.5.4"
val akkaVersion = "2.4.11"
val scalatestVersion = "3.0.0"

lazy val root = project.in(file("."))
  .aggregate(libJS,libJVM)
  .settings(
    scalaVersion := "2.11.8",
    publish := {},
    test := Def.sequential(test in libJVM, test in libJS),
    run := {},
    publishLocal := {}
  )

lazy val lib: CrossProject = crossProject.in(file("."))
  .settings(
    bintrayOrganization := Some("flatmap"),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    name := "scala-json-rpc",
    version := "0.1.3",
    sourceDirectories in Test := Seq.empty,
    scalaVersion := "2.11.8",
    organization := "net.flatmap",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core",
      "io.circe" %%% "circe-generic",
      "io.circe" %%% "circe-parser"
    ).map(_ % circeVersion)
  ).jsSettings(
    test := test in testJS,
    libraryDependencies += "eu.unicredit" %%% "akkajsactorstream" % s"0.$akkaVersion"
  ).jvmSettings(
    test := test in testJVM,
    libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaVersion
  )

lazy val libJS = lib.js
lazy val libJVM = lib.jvm

lazy val macroTest = crossProject.in(file("."))
  .settings(
    scalaVersion := "2.11.8",
    target := target.value / "test",
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestVersion % "test"
  ).dependsOn(lib)

lazy val testJS  = macroTest.js
lazy val testJVM = macroTest.jvm