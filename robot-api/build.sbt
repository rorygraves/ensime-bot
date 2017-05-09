scalaVersion in ThisBuild := "2.11.8"

lazy val root = project.in(file(".")).
  aggregate(robotApiJS, robotApiJVM).
  settings(
    publish := {},
    publishLocal := {}
  )

lazy val robotApi = (crossProject.crossType(CrossType.Pure) in file(".")).
  settings(
    name := "robot-api",
    organization := "io.scalanator",
    version := "0.1",
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    bintrayOrganization := Some("scalanator")
  )

lazy val robotApiJS = robotApi.js
lazy val robotApiJVM = robotApi.jvm
