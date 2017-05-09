package io.scalanator.robot

import akka.actor.{ Actor, ActorLogging, ActorRef }
import ammonite.ops.Path
import io.scalanator.robot.SBTBot.{ ExecuteTask, TaskResult }
import io.scalanator.robotapi._

import scala.concurrent.Promise
import scala.util.Success

class SBTBuildAndTestActor(workspaceRootDir: Path, testNameOpt: Option[String],
    resultPromise: Promise[CheckAnswerResponse], sbtRef: ActorRef) extends Actor with ActorLogging {

  val hasTest = testNameOpt.isDefined
  //  val execCommandArgs = testNameOpt match {
  //    case Some(testName) =>
  //      List("sbt", "-Dsbt.log.noformat=true", "compile", "test:compile", s"testOnly *$testName*")
  //    case None =>
  //      List("sbt", "-Dsbt.log.noformat=true", "compile", "test:compile")
  //
  //  }
  //  val execCommand = ProcessCommand(execCommandArgs).withWorkingDir(workspaceRootDir)

  override def preStart(): Unit = {
    super.preStart()
    log.info("Starting ------------------")
    // start the execution
    testNameOpt match {
      case Some(testName) =>
        log.info(s"Running test '$testName")
      case None =>
        log.info("Running compile")

    }

    sbtRef ! ExecuteTask("compile")
  }

  override def postStop(): Unit = {
    super.postStop()
    log.info("Stopped ------------------")
  }

  override def receive = compileState

  def finishWith(response: CheckAnswerResponse): Unit = {
    log.info("Finishing with state " + response)
    resultPromise.complete(Success(response))
    context.stop(self)
  }

  var compileErrors = Vector[ErrorDetail]()

  def compileState: Receive = {
    case TaskResult(logs) =>
      handleCompileResult(logs)
    case m =>
      val msg = s"Unknown message in Compile state: $m"
      log.error(msg)
      finishWith(CheckAnswerError(msg))
  }

  val compileErrorRegex = """^\[error\] (.*\.scala):(\d+):(.*)$""".r

  private def handleCompileResult(logs: Vector[String]) = {
    if (logs.isEmpty) {
      finishWith(CheckAnswerError("No logs generated for compile task"))
    }

    val lastMessage = logs.last
    if (lastMessage.startsWith("[success]")) {
      sbtRef ! ExecuteTask("test:compile")
      context.become(testCompileState, discardOld = true)
    } else {
      val errors = logs.flatMap {
        case compileErrorRegex(file, line, error) =>
          List(ErrorDetail(file, line.toInt, error.trim))
        case _ => Nil
      }
      finishWith(CheckAnswerCompilationFailed(errors.toList))
    }
  }

  private def handleTestCompileResult(logs: Vector[String]) = {
    if (logs.isEmpty) {
      finishWith(CheckAnswerError("No logs generated for test compile task"))
    }

    val lastMessage = logs.last
    if (lastMessage.startsWith("[success]")) {

      log.info("Test Compile finished")
      testNameOpt match {
        case Some(testName) =>
          sbtRef ! ExecuteTask(s"testOnly *$testName*")
          context.become(testState, discardOld = true)
        case None =>
          log.info("No tests to run - completing")
          finishWith(CheckAnswerPassed)
      }
    } else {
      val errors = logs.flatMap {
        case compileErrorRegex(file, line, error) =>
          List(ErrorDetail(file, line.toInt, error.trim))
        case _ => Nil
      }
      finishWith(CheckAnswerCompilationFailed(errors.toList))
    }
  }

  private def handleTestResult(logs: Vector[String]) = {
    if (logs.isEmpty) {
      finishWith(CheckAnswerError("No logs generated for test task"))
    }

    val lastMessage = logs.last
    if (lastMessage.startsWith("[success]")) {
      finishWith(CheckAnswerPassed)
    } else {
      val errors = logs.flatMap {
        case testRegex(message) =>
          List(message)
        case testRegexNoLocation(message) =>
          List(message)
        case _ => Nil
      }
      finishWith(CheckAnswerTestFailed(errors.toList))
    }
  }

  private def testCompileState: Receive = {
    case TaskResult(logs) =>
      handleTestCompileResult(logs)
    case m =>
      val msg = s"Unknown message in TestCompile state: $m"
      log.error(msg)
      finishWith(CheckAnswerError(msg))
  }

  private def testState: Receive = {
    case TaskResult(logs) =>
      handleTestResult(logs)
    case m =>
      val msg = s"Unknown message in Test state: $m"
      log.error(msg)
      finishWith(CheckAnswerError(msg))
  }

  private val testMarker = "TESTFAIL"

  private val testRegex = """^.*TESTFAIL(.*)\(.*\)$""".r
  private val testRegexNoLocation = """^.*TESTFAIL(.*)$""".r
}
