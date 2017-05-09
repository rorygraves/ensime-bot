package io.scalanator.robot.environment

import io.scalanator.robot.BuildInfo

object Environment {
  def info: Seq[String] = Seq(
    "Environment:",
    s"  OS : $osVersion",
    s"  Java : $javaVersion",
    s"  Scala version: $scalaVersion",
    s"  Built with Scala version: ${BuildInfo.scalaVersion}",
    s"  Built with sbt version: ${BuildInfo.sbtVersion}",
    s"  Built from git sha: ${BuildInfo.gitSha}",
    s"  Built on: ${BuildInfo.builtAtString}"
  )

  private def osVersion: String =
    System.getProperty("os.name")

  private def javaVersion: String = {
    val vmInfo = System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
    val rtInfo = System.getProperty("java.runtime.name") + " " + System.getProperty("java.runtime.version")
    vmInfo + ", " + rtInfo
  }

  private def scalaVersion: String =
    scala.util.Properties.versionString
}
