package io.scalanator.util.process

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import io.scalanator.util.process.ProcessExecutor.CheckStatus

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

object ProcessExecutor {
  def props(command: ProcessCommand, watcher: ActorRef): Props = {
    Props(new ProcessExecutor(command, watcher))
  }
  case object CheckStatus
}

/**
 * Execute a command forwarding all relevant events to the watcher (io/termination etc)
 * @param command The command to execute
 * @param watcher The watcher ref to send process events to.
 */
class ProcessExecutor(command: ProcessCommand, watcher: ActorRef) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.system.dispatcher
  private var process: Option[Process] = None
  private var outReader: Option[InputStreamLineReader] = None
  private var errReader: Option[InputStreamLineReader] = None

  var timer: Option[Cancellable] = None

  override def preStart(): Unit = {
    super.preStart()
    log.info("Starting")
    startProcess()
  }

  var processTerminated = false
  var outTerminated = false
  var errTerminated = false

  private def processIOStream(isr: Option[InputStreamLineReader], ioSource: IOSource): Unit = {
    isr.foreach { r =>
      r.read().foreach { line =>
        watcher ! ProcessIO(ioSource, line)
      }
    }
  }

  private def closeIOStream(isr: Option[InputStreamLineReader], ioSource: IOSource): Unit = {
    isr.foreach { r =>
      r.close().foreach { line =>
        watcher ! ProcessIO(ioSource, line)
      }

    }
  }

  def check(): Unit = {
    processIOStream(outReader, StdOutIOSource)
    processIOStream(errReader, StdErrIOSource)
    process match {
      case Some(p) =>
        // calling exitValue and handling failure works better than isComplete apparently.
        Try(p.exitValue()) match {
          case Success(exitCode) =>
            closeIOStream(outReader, StdOutIOSource)
            closeIOStream(errReader, StdErrIOSource)
            watcher ! ProcessExited(exitCode)
            log.info(s"Process exited with exit code $exitCode")
            context.stop(self)
          case Failure(_) =>
          // do nothing
        }

      case None =>
        log.error("Illegal state, should not be in check without process")
    }
  }
  override def receive: Receive = {
    case CheckStatus =>
      check()
    case m =>
      log.error(s"Unknown message received $m from $sender")
      watcher ! ProcessError(s"Unknown message $m")
      context.stop(self)
  }

  override def postStop(): Unit = {
    log.info("postStop")
    println("")
    outReader.foreach { s => Try(s.close()) }
    errReader.foreach { s => Try(s.close()) }
    process.foreach { _.destroyForcibly() }
    timer.foreach(_.cancel())
  }
  // mutates the vars so shutdown is performed correctly
  def startProcess() = {
    log.info("Starting process " + command)

    val builder = new java.lang.ProcessBuilder()
    builder.environment().putAll(collection.JavaConversions.mapAsJavaMap(command.envArgs))

    // set working dir if defined
    command.workingDir match {
      case Some(wd) =>
        builder.directory(new java.io.File(wd.toString))
      case None =>
    }
    val process = builder.command(command.cmd: _*).start()
    this.process = Some(process)
    // create all the things
    outReader = Some(new InputStreamLineReader(process.getInputStream, "OUT"))
    errReader = Some(new InputStreamLineReader(process.getErrorStream, "ERR"))
    log.info("Process started successfully")
    import scala.concurrent.duration._
    timer = Some(context.system.scheduler.schedule(250.millis, 200.millis, context.self, CheckStatus))
  }
}
