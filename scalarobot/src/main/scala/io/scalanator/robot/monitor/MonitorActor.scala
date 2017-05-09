package io.scalanator.robot.monitor

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ Actor, ActorLogging, ActorSystem, OneForOneStrategy, Props }

final case class ShutdownRequest(reason: String, isError: Boolean = false)

class MonitorActor(mainProps: Props, shutdownFn: (ActorSystem, ShutdownRequest) => Unit) extends Actor with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy() {
    case ex: Exception =>
      log.error(s"Error seen  by monitor actor ${ex.getMessage}", ex)
      self ! ShutdownRequest(s"Monitor actor failed with ${ex.getClass} - ${ex.toString}", isError = true)
      Stop
  }

  def initialiseChildren(): Unit = {

    context.actorOf(mainProps, "robot")

    //    Environment.info foreach log.info
  }

  override def preStart(): Unit = {
    try {
      initialiseChildren()
    } catch {
      case t: Throwable =>
        log.error(s"Error during startup - ${t.getMessage}", t)
        self ! ShutdownRequest(t.toString, isError = true)
    }
  }
  override def receive: Receive = {
    case req: ShutdownRequest =>
      triggerShutdown(req)
  }

  def triggerShutdown(request: ShutdownRequest): Unit = {
    shutdownFn(context.system, request)
  }
}
