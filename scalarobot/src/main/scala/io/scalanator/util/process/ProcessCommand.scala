package io.scalanator.util.process

import ammonite.ops.Path

case class ProcessCommand(
    cmd: Seq[String],
    envArgs: Map[String, String] = Map.empty,
    workingDir: Option[Path] = None
) {

  def withWorkingDir(workingDir: Path) = this.copy(workingDir = Some(workingDir))

  def withEnv(key: String, value: String) = this.copy(envArgs = envArgs + (key -> value))
}

