package io.scalanator.robot

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import akka.io.Tcp._
import akka.io.{ IO, Tcp }
import akka.testkit.{ TestKit, TestProbe }
import akka.util.ByteString
import ammonite.ops.{ Path, write }
import io.scalanator.robot.SBTBot._
import io.scalanator.robot.sbtbot._
import io.scalanator.util.process._

object SBTBot {

  sealed trait SBTBotMessage
  final case class SBTError(msg: String) extends SBTBotMessage
  final case class SBTExited(exitCode: Int) extends SBTBotMessage
  case object SBTBotReady extends SBTBotMessage

  final case class ExecuteTask(task: String)

  final case class TaskResult(io: Vector[String])

  def props(workspaceRootDir: Path): Props = {
    Props(new SBTBot(workspaceRootDir))
  }

  def main(args: Array[String]): Unit = {
    implicit val as = ActorSystem("test")
    val tk = new TestKit(as)
    import ammonite.ops._

    val proxy = TestProbe()
    val parent = as.actorOf(Props(new Actor {
      //      val child = context.actorOf(SBTBot.props(root / "workspace" / "scalanator-akka" / "workspace"), "sbtbot")
      val child = context.actorOf(SBTBot.props(root / "workspace" / "slick-course" / "workspace"), "sbtbot")
      def receive = {
        case x if sender == child => proxy.ref forward x
        case x => child forward x
      }
    }))

    import scala.concurrent.duration._
    //    val actorRef = TestActorRef[SBTBot](SBTBot.props(root / "workspace" / "scalanator-akka" / "workspace"))

    proxy.expectMsg(15.seconds, SBTBotReady)
    println("SBT Bot ready - triggering compile")

    //    sendToSBT(Execution(s"; compile ; test:compile ; testOnly *$testName*", "123"))
    //    sendToSBT(Execution("; clean ; compile","1234"))

    val testName = "ActionCompositionSpec"

    parent ! ExecuteTask("clean")
    proxy.expectMsgClass(15.seconds, classOf[TaskResult])

    parent ! ExecuteTask("compile")
    proxy.expectMsgClass(15.seconds, classOf[TaskResult])

    parent ! ExecuteTask("test:compile")
    proxy.expectMsgClass(15.seconds, classOf[TaskResult])

    parent ! ExecuteTask(s"testOnly *$testName*")
    proxy.expectMsgClass(15.seconds, classOf[TaskResult])

    println("Finished")
    as.terminate()
  }
}

class SBTBot private (workspaceRootDir: Path) extends Actor with ActorLogging {

  implicit val actorSystem: ActorSystem = context.system
  val execCommandArgs = List("sbt", "-Dsbt.log.noformat=true", "compile", "test:compile", "server")

  val execCommand = ProcessCommand(execCommandArgs).withWorkingDir(workspaceRootDir)

  override def preStart: Unit = {
    write.over(workspaceRootDir / "project" / "build.properties", "sbt.version=1.0.2-SNAPSHOT")
    write.over(workspaceRootDir / "project" / "plugins.sbt", "")
    log.info(s"Executing command: $execCommand")
    context.actorOf(ProcessExecutor.props(execCommand, context.self), "exec")
  }

  override def receive: Receive = initReceive

  def initReceive: Receive = {

    case ProcessError(error) =>
      log.warning(s"Got process error $error")
      context.parent ! SBTError(error)
    case ProcessExited(exitCode) =>
      log.warning(s"Got exit code $exitCode")
      context.parent ! SBTExited(exitCode)
    case ProcessIO(source, content) =>
      log.info(s"SBTBOT:${source.shortName}: $content")
      if (content.contains("sbt server started at")) {
        val port = content.replace("[info] sbt server started at 127.0.0.1:", "").trim.toInt
        val addr = new InetSocketAddress("127.0.0.1", port)
        log.info(s"Port = $port initialising connection")
        IO(Tcp) ! Connect(addr)
        context.become(connectingReceive, discardOld = true)
      }
    case x => log.info("Don't know what to do with {}", x)
  }

  var socketConnection: ActorRef = _

  def connectingReceive: Receive = {
    case CommandFailed(_: Connect) =>
      log.error("TCP Command failed to sbt instance")
      context stop self
    case c @ Connected(remote, local) =>
      log.info("Connected!")
      socketConnection = sender()

      socketConnection ! Register(self, keepOpenOnPeerClosed = false, useResumeWriting = false)
      println("Context.parent = " + context.parent)
      context.parent ! SBTBotReady
      context.become(idleReceive, discardOld = true)
    case Received(data) =>
      log.info(s"Got data from sbt: $data")
    case ProcessIO(source, content) =>
      log.info(s"SBTBOT:${source.shortName}: $content")
    case _: ConnectionClosed =>
      log.error("Lost connection to sbt - closing")
      context stop self
    case x =>
      log.info("Don't know what to do with {}", x)
  }

  def sendToSBT(c: SBTCommand): Unit = {
    val msg = Serialization.serialize(c) ++ ByteString("\n")
    socketConnection ! Write(msg)
  }

  var current = ByteString()
  def handleIncomingSBTBytes(bs: ByteString): Unit = {
    current = current ++ bs
    if (current.contains('\n')) {
      val msgBS = current.takeWhile(_ != '\n')
      current = current.dropWhile(_ != '\n').drop(1)
      Serialization.deserialiseEvent(msgBS) match {
        case Left(msg) =>
          log.error(s"Failed to decode message: ${msgBS.decodeString("UTF-8")}: msg")
          context.stop(self)
        case Right(e) =>
          self ! e
      }
    }
  }

  def idleReceive: Receive = {
    case ExecuteTask(task: String) =>
      startTask(task, context.sender)
    case Received(data) =>
      handleIncomingSBTBytes(data)
    case ProcessError(error) =>
      log.warning(s"IDLE Got process error $error")
      context.parent ! SBTError(error)
    case ProcessExited(exitCode) =>
      log.warning(s"IDLE Got exit code $exitCode")
      context.parent ! SBTExited(exitCode)
    case ProcessIO(source, content) =>
      log.info(s"IDLE Got process IO $source $content")
    case x =>
      log.info("IDLE Don't know what to do with {}", x)
  }

  override def postStop(): Unit = {
    log.info("POST STOP -----------------------------------")
    sendToSBT(Execution("exit", "123"))
    Thread.sleep(500)
    log.info("POST STOP2 -----------------------------------")
  }

  var requestor: ActorRef = _
  var io = Vector[String]()
  var seenComplete = false
  var seenIOTotalTime = false

  def startTask(task: String, requestor: ActorRef): Unit = {
    sendToSBT(Execution(task, "123"))
    this.requestor = requestor
    seenComplete = false
    io = Vector.empty
    seenIOTotalTime = false
    context.become(activeReceive, discardOld = true)
  }

  def checkActiveComplete(): Unit = {
    if (seenIOTotalTime && seenComplete) {
      log.info("Stage complete")
      requestor ! TaskResult(io)
      io = Vector.empty
      context.become(idleReceive, discardOld = true)
    }
  }
  def activeReceive: Receive = {
    case e: SBTEvent =>
      log.info(s"Got event $e")
      e match {
        case StatusEvent(Processing(command, queue)) =>
          log.info(s"ACT  SBTBot processing command: $command")
        case StatusEvent(Ready) =>
          seenComplete = true
          checkActiveComplete()
      }
    case Received(data) =>
      handleIncomingSBTBytes(data)
    case ProcessError(error) =>
      log.warning(s"ACT Got process error $error")
      context.parent ! SBTError(error)
    case ProcessExited(exitCode) =>
      log.warning(s"ACT Got exit code $exitCode")
      context.parent ! SBTExited(exitCode)
    case ProcessIO(source, content) =>
      log.info(s"ACT Got process IO $source $content")
      io :+= content
      if (io.last.contains("] Total time: "))
        seenIOTotalTime = true
      checkActiveComplete()
    case x =>
      log.info("ACT Don't know what to do with {}", x)
  }

}
