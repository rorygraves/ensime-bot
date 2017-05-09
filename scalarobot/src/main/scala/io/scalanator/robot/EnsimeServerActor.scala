package io.scalanator.robot

import akka.actor._
import ammonite.ops._
import io.scalanator.robot.EnsimeServerActor.EnsimeProcessStarted
import io.scalanator.util.process.{ ProcessExecutor, ProcessExited, ProcessIO }
import org.ensime.api.{ EnsimeServerMessage, RpcRequest }
import org.ensime.client._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object EnsimeServerActor {

  def props(workingDir: Path): Props = Props(new EnsimeServerActor(workingDir))

  case object EnsimeProcessStarted

  case class EnsimeProcessStartupFailed(msg: String)
}

/**
 * An actor for managing an EnsimeServer instance
 *
 * @param workingDir The project workspace
 */
class EnsimeServerActor(workingDir: Path) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.system.dispatcher
  var processRef: Option[Process] = None
  var ensimeProcessRef: Option[ActorRef] = None
  var ensimeConnRef: Option[ActorRef] = None

  val wsc = new EnsimeServerStartup(
    context.system,
    workingDir, MemoryConfig(512, 512, 128, 64)
  )

  override def preStart(): Unit = {
    super.preStart()
    startSaveClasspath()
  }

  def startSaveClasspath(): Unit = {
    wsc.mkdirs()
    val saveClasspathCommand = wsc.saveClasspathCommand
    context.actorOf(ProcessExecutor.props(saveClasspathCommand, context.self), "saveClasspathCmd")
  }

  def startGenEnsime(): Unit = {
    val genEnsimeCommand = wsc.genEnsimeCommand
    context.actorOf(ProcessExecutor.props(genEnsimeCommand, context.self), "genEnsime")
    context.become(genEnsimeReceive, discardOld = true)
  }

  var startupChecker: Option[Cancellable] = None
  case object CheckStartup

  def startServer(): Unit = {
    val serverCommand = wsc.serverCommand
    ensimeProcessRef = Some(context.actorOf(ProcessExecutor.props(serverCommand, context.self), "server"))
    startupChecker = Some(context.system.scheduler.schedule(2.seconds, 2.seconds, context.self, CheckStartup))
    context.become(serverStartupReceive, discardOld = true)
  }

  def initialiseConnection(httpPort: Int, retryAttempt: Int = 0): Unit = {
    log.info(s"Http port resolved to $httpPort")
    log.info("Initialising websocket connection to ensime server")
    val connRef = context.actorOf(Props(classOf[EnsimeClientWebSocketActor], "127.0.0.1", httpPort, "/jerky"), "conn")
    context.watch(connRef)
    this.ensimeConnRef = Some(connRef)
    context.become(initialiseConnectionReceive(httpPort, retryAttempt, connRef), discardOld = true)
  }

  object ReconnectRequest
  val initialiseAttempts = 0

  def initialiseConnectionReceive(httpPort: Int, initialiseAttempt: Int, connRef: ActorRef): Receive = {
    case Terminated(ref) if connRef == ref =>
      log.info("Connection to ensime server terminated in initialiseConnectionState")
      if (initialiseAttempt > 5) {
        log.error("********************************************")
        log.error("")
        log.error("Failed to connect after 5 attempts - bailing")
        log.error("")
        log.error("********************************************")
        context.stop(self)
      } else
        context.system.scheduler.schedule(2.seconds, 2.seconds, context.self, ReconnectRequest)
    case ReconnectRequest =>
      initialiseConnection(httpPort, initialiseAttempt + 1)
    case HeartbeatReceived =>
      log.info("Successfully received heartbeat - notifying parent, moving to running state")
      context.parent ! EnsimeProcessStarted
      context.become(runningReceive(connRef), discardOld = true)
  }

  override def receive: Receive = saveClasspathReceive

  def serverStartupReceive: Receive = {
    case ProcessExited(pExitCode) =>
      log.error(s"Server process terminated during startup with code $pExitCode")
      context.stop(self)
    case ProcessIO(source, content) =>
      log.info(s"ENSIME:s$source: $content")
    case CheckStartup =>
      wsc.readHttpPort match {
        case Some(port) =>
          startupChecker.foreach(_.cancel())
          initialiseConnection(port)
        case None =>
        // do nothing
      }
  }

  def genEnsimeReceive: Receive = {
    case ProcessExited(exitCode) =>
      if (exitCode != 0) {
        log.error("genEnsime failed with non-zero exit code")
        context.stop(self)
      } else {
        log.info("genEnsime complete")
        startServer()
      }
    case ProcessIO(source, content) =>
      log.info(s"SAVECLASSPATH:${source.shortName}: $content")
  }

  def saveClasspathReceive: Receive = {
    case ProcessExited(exitCode) =>
      println("SC PROCESS COMPLETE")
      if (exitCode != 0) {
        log.error("SaveClasspath failed with non-zero exit code")
        context.stop(self)
      } else {
        log.info("Save classpath complete")
        startGenEnsime()
      }
    case ProcessIO(source, content) =>
      log.info(s"SAVECLASSPATH:${source.shortName}: $content")
  }

  override def postStop(): Unit = {
    log.info("In post stop")
    log.info("Shutting down ensime server connection actor")
    ensimeConnRef.foreach(_ ! PoisonPill)
    super.postStop()
  }

  // TODO periodically check server is alive
  def runningReceive(ensimeServerRef: ActorRef): Receive = {
    case HeartbeatReceived => // do nothing for now
    case Terminated(ref) =>
      if (ensimeConnRef.contains(ref)) {
        log.warning("Ensime websocket connection terminated")
      } else
        log.warning("Ensime client terminated, shutting down")
      context.stop(self)
    case x: RpcRequest =>
      log.info("Got ensime server ")
      // forward the requests
      ensimeServerRef.forward(x)
    case m: EnsimeServerMessage =>
      context.parent ! m
    case ProcessIO(source, contents) =>
      log.info(s"ENSIME:${source.shortName}: $contents")
    case x =>
      log.warning(s"Unknown message $x in state RUNNING")
  }
}
