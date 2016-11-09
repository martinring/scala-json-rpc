import org.scalajs.sbtplugin.cross.CrossProject
import sbt.Keys._

scalaVersion in ThisBuild := "2.11.8"

val versions = new {
  val circe = "0.6.0-RC1"
  val akka = "2.4.12"
  val scalatest = "3.0.0"
  val scala = "2.11.8"
}

lazy val root = project.in(file("."))
  .aggregate(libJS,libJVM)
  .settings(
    scalaVersion := versions.scala,
    publish := {},
    publishLocal := {}
  )

lazy val lib: CrossProject = crossProject.in(file("."))
  .settings(
    bintrayOrganization := Some("flatmap"),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    name := "jsonrpc",
    version := "0.5.1",
    sourceDirectories in Test := Seq.empty,
    scalaVersion := versions.scala,
    organization := "net.flatmap",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core",
      "io.circe" %%% "circe-generic",
      "io.circe" %%% "circe-parser"
    ).map(_ % versions.circe),
    libraryDependencies += "org.scalatest" %%% "scalatest" % versions.scalatest % "test"
  ).jsSettings(
    libraryDependencies += "eu.unicredit" %%% "akkajsactorstream" % ("0." + versions.akka)
  ).jvmSettings(
    //crossScalaVersions := Seq("2.12.0-RC2"),
    libraryDependencies += "com.typesafe.akka" %% "akka-stream" % versions.akka
  )

lazy val libJS = lib.js
lazy val libJVM = lib.jvm
