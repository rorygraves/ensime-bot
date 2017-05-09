package io.scalanator.robot.sbtbot

sealed trait SBTEvent

final case class LogEvent(level: String, message: String) extends SBTEvent

sealed trait SBTStatus

case object Ready extends SBTStatus
final case class Processing(command: String, commandQueue: Seq[String]) extends SBTStatus

final case class StatusEvent(status: SBTStatus) extends SBTEvent
final case class ExecutionEvent(command: String, success: Boolean) extends SBTEvent

sealed trait SBTCommand

/**
 * An execution request - command line is the same as typed in at the command prompt
 * e.g. { "type": "exec", "command_line": "compile", "id": "29bc9b"  }
 *
 * @param cmd
 * @param id Client generated unique id, related response events will carry this id.
 */
final case class Execution(cmd: String, id: String) extends SBTCommand
