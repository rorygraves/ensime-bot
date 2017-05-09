package io.scalanator.robot.sbtbot

import akka.util.ByteString
import org.json4s.JsonAST.{ JArray, JString }
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.ParserUtil.ParseException

object Serialization {

  def serialize(event: SBTEvent): Array[Byte] = {
    compact(render(toJson(event))).getBytes("UTF-8")
  }

  def serialize(cmd: SBTCommand): ByteString = {
    ByteString(compact(render(toJson(cmd))), "UTF-8")
  }

  def toJson(event: SBTEvent): JObject = event match {
    case LogEvent(level, message) =>
      JObject(
        "type" -> JString("log_event"),
        "level" -> JString(level),
        "message" -> JString(message)
      )

    case StatusEvent(Ready) =>
      JObject(
        "type" -> JString("status_event"),
        "status" -> JString("ready"),
        "command_queue" -> JArray(List.empty)
      )

    case StatusEvent(Processing(command, commandQueue)) =>
      JObject(
        "type" -> JString("status_event"),
        "status" -> JString("processing"),
        "command" -> JString(command),
        "command_queue" -> JArray(commandQueue.map(JString).toList)
      )

    case ExecutionEvent(command, status) =>
      JObject(
        "type" -> JString("execution_event"),
        "command" -> JString(command),
        "success" -> JBool(status)
      )
  }

  def toJson(command: SBTCommand): JObject = command match {
    case Execution(command, id) =>
      JObject(
        "type" -> JString("exec"),
        "command_line" -> JString(command),
        "id" -> JString(id)
      )
  }

  def deserialiseEvent(data: ByteString): Either[String, SBTEvent] = {
    try {
      val json = parse(new String(data.decodeString("UTF-8")))
      println("JSON = " + json)
      implicit val readers = DefaultReaders
      implicit val formats = DefaultFormats

      import DefaultReaders._

      (json \ "type").toOption match {
        case Some(JString("status_event")) =>
          val status = (json \ "status").as[String]
          val commandQueue = (json \ "command_queue").as[List[String]]
          status match {
            case "ready" =>
              Right(StatusEvent(Ready))
            case "processing" =>
              val command = (json \ "command").as[String]
              Right(StatusEvent(Processing(command, commandQueue)))
            case _ =>
              Left(s"Unknown status event type $status")
          }
        case Some(cmd) => Left(s"Unknown event type $cmd")
        case None => Left("Invalid event, missing type field")
      }
    } catch {
      case e: ParseException => Left(s"Parse error: ${e.getMessage}")
    }

  }
  /**
   * @return A command or an invalid input description
   */
  def deserialize(data: ByteString): Either[String, SBTCommand] =
    try {
      val json = parse(new String(data.decodeString("UTF-8")))
      implicit val formats = DefaultFormats

      (json \ "type").toOption match {
        case Some(JString("exec")) =>
          (json \ "command_line").toOption match {
            case Some(JString(cmd)) => Right(Execution(cmd, ""))
            case _ => Left("Missing or invalid command_line field")
          }
        case Some(cmd) => Left(s"Unknown command type $cmd")
        case None => Left("Invalid command, missing type field")
      }
    } catch {
      case e: ParseException => Left(s"Parse error: ${e.getMessage}")
    }
}
