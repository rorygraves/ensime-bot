package io.scalanator.robotapi

object MessageUtil {
  def displayString(s: String): String = {
    val raw = if (s.length <= 20)
      s
    else
      s.take(20) + "..."

    raw.replace("\n", "\\n")
  }
}

import MessageUtil._

sealed trait Message

case object Heartbeat extends Message
case object EditorDetached extends Message

sealed trait MessageFromRobot extends Message

case class SyncReqEnvelope(requestId: Int, request: SyncRequest) extends Message
case class SyncRespEnvelope(requestId: Int, response: SyncResponse) extends MessageFromRobot

case class RobotRegistration(name: String, courseName: String, secret: String) extends Message

case class CompilerNote(
  file: String,
  msg: String,
  severity: Severity,
  beg: Int,
  end: Int,
  line: Int,
  col: Int
)

sealed trait Severity
case object Info extends Severity
case object Warning extends Severity
case object Error extends Severity

sealed trait SyncRequest

/**
  * Initialise a workspace source directory with the given fileset
  * @param files Map of path (relative to src root and contents)
  * @param revertAfterRequest If true revert each file after each request
  */
case class InitialiseWorkspaceRequest(files: Map[String, String], revertAfterRequest: Boolean) extends SyncRequest {
  override def toString: String = "InitialiseWorkspaceRequest(files...)"
}

case class TypeCheckFileRequest(file: String, contents: String) extends SyncRequest {
  override def toString: String = s"""TypeCheckFileRequest("$file","${contents.take(20)}...")"""
}

// returns all active files (paths relative to the source root)
case object ListSourcesRequest extends SyncRequest
case class TypeAtRequest(file: String, contents: String, point: Int) extends SyncRequest

case class CompletionsRequest(file: String, contents: String, point: Int,
  maxResults: Int, caseSens: Boolean, reload: Boolean) extends SyncRequest {
  override def toString: String = s"""CompletionRequest("$file","${contents.take(20)}...)"""
}
case class ReadFileContentsRequest(file: String) extends SyncRequest

case class CheckAnswerRequest(filename: String, code: String, testName: String) extends SyncRequest {
  override def toString: String = s"""CheckAnswerRequest("$filename","${code.take(20)}...,"$testName")"""
}

sealed trait SyncResponse
// used to signal the failure of a request
case class FailureResponse(message: String) extends SyncResponse

sealed trait InitialiseWorkspaceResponse extends SyncResponse

case object InitialiseWorkspaceSuccessResponse extends InitialiseWorkspaceResponse
case class InitialiseWorkspaceFailedResponse(msg: String) extends InitialiseWorkspaceResponse

case class ErrorDetail(file: String, line: Int, message: String)

sealed trait CheckAnswerResponse extends SyncResponse

case class CheckAnswerError(error: String) extends CheckAnswerResponse
case class CheckAnswerCompilationFailed(errors: List[ErrorDetail]) extends CheckAnswerResponse
case class CheckAnswerTestCompilationFailed(errors: List[ErrorDetail]) extends CheckAnswerResponse
case class CheckAnswerTestFailed(errors: List[String]) extends CheckAnswerResponse
case object CheckAnswerPassed extends CheckAnswerResponse

case class TypeCheckFileResponse(notes: List[CompilerNote], time: Long) extends SyncResponse
case class ListSourcesResponse(files: List[String]) extends SyncResponse
case class TypeAtResponse(result: Option[String], time: Long) extends SyncResponse
case class CompletionsResponse(result: List[(String, String)], time: Long) extends SyncResponse {
  override def toString = s"""CompletionResponse(${displayString(result.toString)},$time)"""
}
case class ReadFileContentsResponse(file: String, contents: Option[String]) extends SyncResponse
