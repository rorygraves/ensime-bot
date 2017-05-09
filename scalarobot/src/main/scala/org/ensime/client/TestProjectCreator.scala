package org.ensime.client

import ammonite.ops._
import org.slf4j.LoggerFactory

object TestProjectCreator {
  val logger = LoggerFactory.getLogger("TestProjectCreator")

  def projectBuildProps = "sbt.version=0.13.11\n"

  def createTestProject(projectBase: Path, scalaVersion: String): Unit = {

    logger.info(s"Creating project in $projectBase")

    val projectDir = projectBase / "project"
    val projectBuildPropsFile = projectDir / "build.properties"

    val srcDir = projectBase / "src" / "main" / "scala"
    val buildSBTFile = projectBase / "build.sbt"

    val testFile = srcDir / "test.scala"

    def sbtFileContents =
      s"""|
         |name := "Robot project template"
          |version := "0.1.0"
          |scalaVersion := "$scalaVersion"
          |organization := "na"
          |libraryDependencies ++= {
          |  	Seq(
          |  	)
          |}
          |mainClass := Some("MyApp")
          |
         |resolvers ++= Seq("snapshots"     at "http://oss.sonatype.org/content/repositories/snapshots",
          |                "releases"        at "http://oss.sonatype.org/content/repositories/releases"
          |                )
          |
         |scalacOptions ++= Seq("-unchecked", "-deprecation")
          |""".stripMargin

    mkdir ! projectDir
    mkdir ! srcDir
    println(s"Writing build file $buildSBTFile")
    write(buildSBTFile, sbtFileContents)
    write(
      testFile,
      """//test code here
        |val a = 1
        |val b = 2
        |println(a + b)
        |
      """.stripMargin
    )
    println(s"Writing props file to $projectBuildPropsFile")
    write(projectBuildPropsFile, projectBuildProps)
    val projectPluginsFile = projectDir / "plugins.sbt"
    write(
      projectPluginsFile,
      """|    // ensime-sbt is needed for the integration tests
        |addSbtPlugin("org.ensime" % "ensime-sbt" % "0.2.3")
        |""".stripMargin
    )
  }
}
