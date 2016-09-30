val circeVersion = "0.5.1"

lazy val root = project.in(file("."))
  .aggregate(libJS,libJVM)
  .settings(
    run := (run in libJVM),
    publish := {},
    publishLocal := {}
  )

lazy val lib = crossProject.in(file("."))
  .settings(
    name := "scala-json-rpc",
    version := "0.1",
    organization := "net.flatmap",
    scalaVersion := "2.11.8",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
  ).jsSettings(
    libraryDependencies += "eu.unicredit" %%% "akkajsactorstream" % "0.2.4.10"
  ).jvmSettings(
    libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.4.10"
  )

lazy val libJS = lib.js
lazy val libJVM = lib.jvm