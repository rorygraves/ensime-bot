package org.ensime.client

import akka.actor.{ ActorLogging, ActorRef, Cancellable }
import akka.io.Tcp.{ ErrorClosed, PeerClosed }
import org.ensime.api._
import org.ensime.jerk.JerkEnvelopeFormats
import org.suecarter.websocket.WebSocketClient
import spray.can.websocket.UpgradedToWebSocket
import spray.can.websocket.frame.TextFrame

import scala.concurrent.ExecutionContext

case object Heartbeat
case object HeartbeatReceived

class EnsimeClientWebSocketActor(host: String, port: Int, path: String) extends WebSocketClient(host, port, path) with ActorLogging {

  import JerkEnvelopeFormats._
  import spray.json._

  implicit val ec: ExecutionContext = context.system.dispatcher

  private var nextId = 1
  private var requests = Map[Int, ActorRef]()
  private var connectionInfo: Option[ConnectionInfo] = None

  def handleRPCResponse(id: Int, payload: EnsimeServerMessage) = {
    requests.get(id) match {
      case Some(ref) =>
        requests -= id
        log.info("Got response for request " + id)
        ref ! payload
      case _ =>
        log.warning(s"Got response without requester $id -> $payload")
    }
  }

  def handleAsyncMessage(payload: EnsimeServerMessage) = {
    payload match {
      case e: EnsimeEvent =>
        context.parent ! payload
      case _ =>
        log.error(s"Illegal state - received async message for non-async event: $payload")
    }
  }

  var hbRef: Option[Cancellable] = None

  def scheduleHeartbeat(): Unit = {
    import scala.concurrent.duration._
    hbRef = Some(context.system.scheduler.schedule(30.seconds, 30.seconds, self, Heartbeat))
  }

  override def postStop(): Unit = {
    hbRef.foreach(_.cancel())
  }

  def sendToEnsime(rpcRequest: RpcRequest, sender: ActorRef): Unit = {
    val id = nextId
    nextId += 1
    requests += (id -> sender)
    val env = RpcRequestEnvelope(rpcRequest, id)
    log.info(s"Sending $env")
    val json = env.toJson.prettyPrint
    connection ! TextFrame(json)
  }

  def websockets: Receive = {
    case c: ConnectionInfo =>
      if (connectionInfo.isEmpty)
        connectionInfo = Some(c)
      context.parent ! HeartbeatReceived
    case Heartbeat =>
      sendToEnsime(ConnectionInfoReq, self)
    case r: RpcRequest =>
      sendToEnsime(r, sender)
    case ErrorClosed(cause) =>
      log.error(s"Websocket connection closed (error - $cause, shutting down")
      hbRef.foreach(_.cancel())
      context.stop(self)
    case PeerClosed =>
      log.info("Websocket connection closed, shutting down")
      hbRef.foreach(_.cancel())
      context.stop(self)
    case t: TextFrame =>
      val payload = t.payload.utf8String
      val msg = payload.parseJson.convertTo[RpcResponseEnvelope]
      msg.callId match {
        case Some(id) =>
          handleRPCResponse(id, msg.payload)
        case None =>
          handleAsyncMessage(msg.payload)
      }
    case UpgradedToWebSocket =>
      sendToEnsime(ConnectionInfoReq, self)
      scheduleHeartbeat()
    case x =>
      log.info("Got unknown message: " + x.getClass + "  " + x)
  }
}
