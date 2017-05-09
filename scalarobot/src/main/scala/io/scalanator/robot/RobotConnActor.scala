package io.scalanator.robot

import akka.actor.{ ActorLogging, Cancellable, PoisonPill, Props }
import akka.io.Tcp.ConnectionClosed
import upickle.default._
import io.scalanator.robotapi._
import org.suecarter.websocket.WebSocketClient
import spray.can.websocket.UpgradedToWebSocket
import spray.can.websocket.frame.TextFrame

import scala.concurrent.ExecutionContext

class RobotConnActor(
    name: String,
    host: String,
    port: Int,
    path: String,
    useSSL: Boolean,
    secret: String,
    courseName: String
) extends WebSocketClient(host, port, path, useSSL) with ActorLogging {

  implicit val ec: ExecutionContext = context.system.dispatcher

  case object SendHeartbeat

  def sendToServer(m: Message): Unit = {
    if (m != Heartbeat)
      log.info(s"Sending $m to server")
    val json = write(m)
    connection ! TextFrame(json)
  }

  override def preStart(): Unit = {
    super.preStart()
    log.info("Started")
  }

  override def postStop(): Unit = {
    super.postStop()
    heartbeatRef.foreach(_.cancel())
    log.info("Stopped")
  }

  var heartbeatRef: Option[Cancellable] = None
  override def websockets: Receive = {
    case UpgradedToWebSocket =>
      log.info("connected to server")
      self ! RobotRegistration(name, courseName, secret)
      import scala.concurrent.duration._
      heartbeatRef = Some(context.system.scheduler.schedule(15.seconds, 15.seconds, self, SendHeartbeat))
    case SendHeartbeat =>
      sendToServer(Heartbeat)
    case m: Message =>
      sendToServer(m)
    case t: TextFrame =>
      val payload = t.payload.utf8String
      val msg = read[Message](payload)
      if (msg != Heartbeat) {
        context.parent ! msg
      }
    case x: ConnectionClosed =>
      log.info("Lost connection to server, terminating connection actor")
      self ! PoisonPill
    case x =>
      log.info("Got unknown message: " + x.getClass + "  " + x)
  }
}

object RobotConnActor {
  def props(
    name: String,
    host: String,
    port: Int,
    path: String,
    useSSL: Boolean,
    secret: String,
    courseName: String
  ): Props =
    Props(new RobotConnActor(name, host, port, path, useSSL, secret, courseName))
}
