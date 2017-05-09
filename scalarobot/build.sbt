name := "Scalanator.io robot"

version := "1.0"

scalaVersion := "2.11.8"

enablePlugins(JavaAppPackaging)

enablePlugins(DockerPlugin)

lazy val logback = Seq(
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "org.slf4j" % "slf4j-api" % "1.7.21",
  "org.slf4j" % "jul-to-slf4j" % "1.7.21",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.21"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.7",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.7",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.7",
  "org.ensime" %% "jerky" % "1.0.0",
  "com.github.scopt" %% "scopt" % "3.5.0",
  "org.apache.commons" % "commons-lang3" % "3.4",
  "org.suecarter" % "simple-spray-websockets_2.11" % "1.0.1",
  "com.lihaoyi" %% "upickle" % "0.4.1",
  "com.lihaoyi" %% "ammonite-ops" % "0.7.5",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "org.json4s" % "json4s-native_2.11" % "3.4.0",
  "io.scalanator" %% "robot-api" % "0.1"
) ++ logback

resolvers += Resolver.sonatypeRepo("public")
resolvers += Resolver.bintrayRepo("scalanator", "maven")

mainClass in Compile := Some("io.scalanator.robot.RobotMain")

// set the process memory
javaOptions in Universal ++= Seq(
    // -J params will be added as jvm parameters
    "-J-Xmx128m",
    "-J-Xms128m"
)

dockerBaseImage := "scalanator/robotbase:v1"

import com.typesafe.sbt.packager.docker._

import scala.util.Try

daemonUser in Docker := "robot"

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.14"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoOptions += BuildInfoOption.BuildTime,
    buildInfoKeys += BuildInfoKey.action("gitSha")(Try("git rev-parse --verify HEAD".!! dropRight 1) getOrElse "n/a"),
    buildInfoPackage := "io.scalanator.robot"
  )
