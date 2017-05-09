package io.scalanator.robot

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ ActorRef, ActorSystem, Props }
import ammonite.ops._
import io.scalanator.robotapi.CheckAnswerResponse

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

object SBTRunner {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem()

    //    val checker = new SBTRunner(system, Path("/workspace/slick-course/workspace"))
    //    //    val res = checker.compileAndTest("TableAddRatingSpec")
    //    val res = checker.compileOnly()
    //    val answer = Await.result(res, 60.seconds)
    //    println("RESPONSE = " + answer)
    //    system.terminate()
  }

  private val nextIdGen = new AtomicInteger(0)
}

class SBTRunner(system: ActorSystem, val workspaceRootDir: Path) {

  val relSourceRoot = RelPath("src/main/scala")
  val relTestRoot = RelPath("src/test/scala")
  val sourceRoot = workspaceRootDir / relSourceRoot

  def compileOnly(sbtRef: ActorRef) = {
    runBuild(None, sbtRef)
  }

  def compileAndTest(testName: String, sbtRef: ActorRef): Future[CheckAnswerResponse] = {
    runBuild(Some(testName), sbtRef)
  }

  private def runBuild(testNameOpt: Option[String], sbtRef: ActorRef): Future[CheckAnswerResponse] = {
    val promise = Promise[CheckAnswerResponse]()
    system.actorOf(Props(new SBTBuildAndTestActor(workspaceRootDir, testNameOpt, promise, sbtRef)), s"Checker${SBTRunner.nextIdGen.incrementAndGet()}")
    promise.future
  }
}

