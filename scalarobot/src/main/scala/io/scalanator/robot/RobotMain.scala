package io.scalanator.robot

import java.net.InetAddress

import akka.actor.{ ActorSystem, Props }
import ammonite.ops.{ Path, mkdir }
import io.scalanator.robot.environment.Environment
import io.scalanator.robot.monitor.{ MonitorActor, ShutdownRequest }
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.util.Try

object RobotMain {
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  val logger = LoggerFactory.getLogger("RobotMain")

  def main(args: Array[String]): Unit = {
    logger.info("Scalanator.io RobotMain running")
    // BuildInfo is generated.
    logger.info("***********************************************************")
    Environment.info.foreach(logger.info)
    logger.info("***********************************************************")
    RobotOptions.parse(args) match {
      case Some(opts) =>
        startServer(opts)
      case None =>
        System.exit(1)
      // options parse will have already output error message
    }
  }

  def getShortHostName: String = {
    val base = InetAddress.getLocalHost.getHostName
    base.indexOf('.') match {
      case -1 =>
        base
      case n => base.substring(0, n)
    }
  }

  def startServer(robotOptions: RobotOptions): Unit = {
    logger.info(s"Starting robot instance with options: $robotOptions")

    val workingDir = Path(robotOptions.workDir)

    val name = s"${robotOptions.robotName}-$getShortHostName"

    val host = robotOptions.serverHost
    val port = robotOptions.serverPort
    val path = robotOptions.serverURLPath + robotOptions.courseName
    val useSSL = robotOptions.serverUseSSL
    val secret = robotOptions.secret
    val courseName = robotOptions.courseName
    val system = ActorSystem("r")

    mkdir ! workingDir

    val robotProps = RobotActor.props(name, host, port, path, useSSL, secret, courseName, workingDir)
    system.actorOf(Props(new MonitorActor(robotProps, shutdown)), "monitor")
  }

  def shutdown(system: ActorSystem, request: ShutdownRequest): Unit = {
    val t = new Thread(new Runnable {
      def run(): Unit = {
        if (request.isError)
          logger.error(s"Shutdown requested due to internal error: ${request.reason}")
        else
          logger.info(s"Shutdown requested: ${request.reason}")

        logger.info("Shutting down the ActorSystem")
        Try(system.shutdown())

        logger.info("Awaiting actor system termination")
        import scala.concurrent.duration._
        Try(system.awaitTermination(30.seconds))

        logger.info(s"Shutdown complete - exiting (withError = ${request.isError}")
        if (request.isError)
          System.exit(1)
        else
          System.exit(0)
      }
    })
    t.start()
  }

}
