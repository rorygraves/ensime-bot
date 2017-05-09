package io.scalanator.robot

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import ammonite.ops._
import io.scalanator.robot.EnsimeServerActor.{ EnsimeProcessStarted, EnsimeProcessStartupFailed }
import io.scalanator.robotapi._
import org.ensime.api._

import scala.collection.immutable.Queue
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import TypeRenderHelper._
import RobotActor._
import io.scalanator.robot.SBTBot.SBTBotReady

case object ConnectToServerCmd

class RobotActor(
    robotName: String,
    scalanatorHost: String,
    port: Int,
    urlPath: String,
    useSSL: Boolean,
    secret: String,
    courseName: String,
    val workspaceRootDir: Path
) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.system.dispatcher

  val relSourceRoot = RelPath("src/main/scala")
  val sourceRoot = workspaceRootDir / relSourceRoot

  var webServerConnActor: Option[ActorRef] = None

  var ensimeServerRef: Option[ActorRef] = None
  var startupRequestId: Option[Int] = None
  var activeWorkspace = false
  var workspaceFiles = Map.empty[String, String]
  var revertFilesAfterRequest = false

  var noteSet: Queue[CompilerNote] = Queue.empty

  case class ActiveTypeCheck(id: Int, file: RelPath) {
    val startTime = System.currentTimeMillis()
  }

  var currentTypeCheckReq: Option[ActiveTypeCheck] = None
  var outstandingTypeCheckRequests = Queue[(Int, TypeCheckFileRequest)]()

  override def preStart(): Unit = {
    super.preStart()
    log.info("Started")
    self ! ConnectToServerCmd
    cleardownWorkspaceFiles()
  }

  override def postStop(): Unit = {
    log.info("Stopped")
    super.postStop()
  }

  def revertFileIfNeeded(relFilePath: RelPath): Unit = {
    if (revertFilesAfterRequest) {
      log.info(s"Reverting file $relFilePath")
      val fullPath = workspaceRootDir / relFilePath
      workspaceFiles.get(relFilePath.toString()) match {
        case Some(contents) =>
          val target = fullPath
          write.over(target, contents)
          log.info("Sending unload all to Ensime Server")
          ensimeServerRef.foreach(_ ! UnloadAllReq)
        case None =>
          log.error(s"Unknown rel path for revert $relFilePath")
      }

    }
  }

  def startWebserverConn(): Unit = {
    log.info(s"Starting connection to webserver $scalanatorHost:$port$urlPath (use SSL = $useSSL)")
    val connActorRef = context.actorOf(RobotConnActor.props(robotName, scalanatorHost, port, urlPath, useSSL, secret, courseName), "serverConn")
    webServerConnActor = Some(connActorRef)
    context.watch(connActorRef)
  }

  def sendToServer(m: Message): Unit = {
    webServerConnActor.foreach(_ ! m)
  }

  def cleardownWorkspace(): Unit = {
    ensimeServerRef.foreach(_ ! PoisonPill)
    sbtBotRef.foreach(_ ! PoisonPill)
    ensimeServerRef = None
    sbtBotRef = None
    activeWorkspace = false
    // TODO is there a race condition here waiting for ensimeServer to terminate?
    cleardownWorkspaceFiles()
  }

  def cleardownWorkspaceFiles(): Unit = {
    log.info("Clearing workspace files")
    ls ! workspaceRootDir foreach { e =>
      rm ! e
    }
  }

  def handleOutstandingTypeCheckRequests(): Unit = {
    if (currentTypeCheckReq.isEmpty && outstandingTypeCheckRequests.nonEmpty) {
      val ((reqId, request), newQueue) = outstandingTypeCheckRequests.dequeue
      log.info(s"Starting TypeCheck request $reqId")
      outstandingTypeCheckRequests = newQueue

      val relPath = RelPath(request.file)
      val fileContents = request.contents
      try {
        val filePath = relPathToFullPath(relPath)
        write.over(filePath, fileContents)
        log.info(s"Writing file $filePath")
        val javaFilePath = new java.io.File(filePath.toString)
        askEnsime(TypecheckFileReq(SourceFileInfo(javaFilePath)))
        currentTypeCheckReq = Some(ActiveTypeCheck(reqId, relPath))
      } catch {
        case t: Throwable =>
          log.info("Failed to handle typeCheck request: ", t)
          sendSyncFailResponse(reqId, "Failed typeCheck request: " + t)

          // process any remaining requests
          handleOutstandingTypeCheckRequests()
      }
    }
  }

  def handleEnsimeEvent(e: EnsimeEvent): Unit = {
    log.info("Got ensime event: " + e)
    e match {
      case ClearAllScalaNotesEvent =>
        noteSet = Queue.empty
      case FullTypeCheckCompleteEvent =>
        currentTypeCheckReq match {
          case Some(a @ ActiveTypeCheck(id, filePath)) =>
            val endTime = System.currentTimeMillis()
            val totalTime = endTime - a.startTime
            log.info(s"Completing TypeCheck request $id with ${noteSet.size} notes in ${totalTime}ms")
            sendSyncResponse(id, TypeCheckFileResponse(noteSet.toList, totalTime))
            currentTypeCheckReq = None
            revertFileIfNeeded(filePath)
            handleOutstandingTypeCheckRequests()
          case None =>
        }
      case NewScalaNotesEvent(full, notes) =>
        noteSet ++= notes.map(toCompilerNote)
      case _ =>
      // ignored
    }
  }

  def sendSyncResponse(requestId: Int, syncResponse: SyncResponse): Unit = {
    sendToServer(SyncRespEnvelope(requestId, syncResponse))
  }

  def sendSyncFailResponse(requestId: Int, message: String): Unit = {
    sendToServer(SyncRespEnvelope(requestId, FailureResponse(message)))
  }

  /**
   * Convert a user given relative path to a full path, failing if we detect something naughty (e.g. ..)
   *
   * @param relPath The user given path
   * @return The full Ammonite Path of the file
   * @throws RequestRejectedException if invalid path detected
   */
  def relPathToFullPath(relPath: RelPath): Path = {
    val fullPath = workspaceRootDir / relPath

    if (!fullPath.startsWith(sourceRoot))
      throw new RequestRejectedException("Path is not relative to source root")
    fullPath
  }

  def askEnsime(request: RpcRequest): Future[Any] = {
    ensimeServerRef match {
      case Some(ref) =>
        implicit val timeout: Timeout = 30.seconds
        ref ? request
      case none =>
        Future.failed(new Exception("Ensime server not running"))
    }
  }

  val sbtRunner = new SBTRunner(context.system, workspaceRootDir)

  def checkAnswer(testName: String): Future[CheckAnswerResponse] = {
    sbtRunner.compileAndTest(testName, sbtBotRef.get)
  }

  def handleSyncRequest(requestId: Int, request: SyncRequest) = {
    try {
      request match {
        case r: TypeCheckFileRequest =>
          outstandingTypeCheckRequests :+= (requestId, r)
          handleOutstandingTypeCheckRequests()
        case ListSourcesRequest =>
          val files = (ls.rec ! sourceRoot |? (_.ext == "scala")).toList.map(f => f.relativeTo(sourceRoot).toString())
          sendSyncResponse(requestId, ListSourcesResponse(files))
        case ReadFileContentsRequest(relPathStr: String) =>
          val relPath = RelPath(relPathStr)
          val filePath = relPathToFullPath(relPath)
          val fileContents = if (exists ! filePath)
            Some(read ! filePath)
          else
            None
          sendSyncResponse(requestId, ReadFileContentsResponse(relPathStr, fileContents))
        case TypeAtRequest(relPathStr, contents, point) =>
          val startTime = System.currentTimeMillis()
          val relPath = RelPath(relPathStr)
          val fullPath = relPathToFullPath(relPath)
          val javaFilePath = new java.io.File(fullPath.toString)
          write.over(fullPath, contents)
          implicit val timeout: Timeout = 30.seconds
          val resFut = askEnsime(TypeAtPointReq(Right(SourceFileInfo(javaFilePath)), OffsetRange(point)))
          resFut.onSuccess {
            case ti: TypeInfo =>
              val endTime = System.currentTimeMillis()
              val totalTime = endTime - startTime
              val typeString = renderTypeInfo(ti)
              sendSyncResponse(requestId, TypeAtResponse(Some(typeString), totalTime))
            case _ =>
              val endTime = System.currentTimeMillis()
              val totalTime = endTime - startTime
              sendSyncResponse(requestId, TypeAtResponse(None, totalTime))
          }
          revertFileIfNeeded(relPath)
        case CheckAnswerRequest(relFilePathStr, fileContents, testName) =>
          val relPath = RelPath(relFilePathStr)
          val fullPath = relPathToFullPath(relPath)
          write.over(fullPath, fileContents)
          log.info(s"CheckAnswerRequest - $request")
          println("TESTNAME='" + testName + "'")
          val resultFut = checkAnswer(testName)
          resultFut.onSuccess {
            case r =>
              sendSyncResponse(requestId, r)
              revertFileIfNeeded(relPath)
          }
        case CompletionsRequest(relPathStr, contents, point, maxResults, caseSen, reload) =>
          val startTime = System.currentTimeMillis()
          val relPath = RelPath(relPathStr)
          val fullPath = relPathToFullPath(relPath)
          val javaFilePath = new java.io.File(fullPath.toString)
          write.over(fullPath, contents)

          // N.b. Reload is ignored - we are sending the contents instead
          val resFut = askEnsime(CompletionsReq(SourceFileInfo(javaFilePath, Some(contents)), point, maxResults, caseSen,
            reload = true)).mapTo[CompletionInfoList]
          resFut.onSuccess {
            case res =>
              val endTime = System.currentTimeMillis()
              val totalTime = endTime - startTime
              val result = res.completions.map { c => (c.name, sigToString(c.typeSig)) }
              sendSyncResponse(requestId, CompletionsResponse(result, totalTime))
          }
          revertFileIfNeeded(relPath)
        case _ =>
          sendSyncFailResponse(requestId, s"Unknown request ${request.getClass}")
      }
    } catch {
      case r: RequestRejectedException =>
        log.warning(s"Rejecting request $requestId - ${r.getMessage}")
        sendSyncFailResponse(requestId, s"Request rejected: ${r.getMessage}")
      case t: Throwable =>
        log.error(s"Request $requestId failed with exception", t)
        sendSyncFailResponse(requestId, s"Request failed: ${t.getMessage}")
    }
  }

  def handleServerMessage(m: Message): Unit = {
    m match {
      case SyncReqEnvelope(requestId, InitialiseWorkspaceRequest(files, revertAfterRequest)) =>
        log.info("Got initialise workspace request")
        initialiseWorkspaceIfNeeded(requestId, files, revertAfterRequest)
      case SyncReqEnvelope(requestId, request) =>
        if (activeWorkspace)
          handleSyncRequest(requestId, request)
        else {
          log.info(s"Failed to handle request $request in inactive state")
          sendSyncFailResponse(requestId, "workspace inactive, request failed")
        }
      case EditorDetached =>
        log.info("Editor detached")
      // we leave the workspace active until the next connection
      case _ =>
        log.error(s"Unexpected message: $m")
    }
  }

  def writeWorkspace(files: Map[String, String]): Unit = {
    files.foreach {
      case (fname, contents) =>
        val path = workspaceRootDir / RelPath(fname)
        write.over(path, contents)
    }
  }

  def initialiseWorkspaceIfNeeded(requestId: Int, files: Map[String, String], revertAfterRequest: Boolean): Unit = {
    log.info(s"initialiseWorkspaceIfNeeded: - active=$activeWorkspace, requestId=$requestId")
    if (activeWorkspace && files == workspaceFiles && this.revertFilesAfterRequest == revertFilesAfterRequest) {
      // workspace already configured - no need for changes.
      if (!revertFilesAfterRequest)
        writeWorkspace(files)
      sendSyncResponse(requestId, InitialiseWorkspaceSuccessResponse)
    } else {
      initialiseWorkspace(requestId: Int, files: Map[String, String], revertAfterRequest: Boolean)
    }
  }

  case object StartEnsimeServer

  def initialiseWorkspace(requestId: Int, files: Map[String, String], revertAfterRequest: Boolean): Unit = {
    cleardownWorkspace()
    log.info("Got InitialiseWorkspaceRequest - revertFiles = " + revertAfterRequest)
    writeWorkspace(files)
    this.revertFilesAfterRequest = revertAfterRequest
    this.workspaceFiles = files
    startupRequestId = Some(requestId)

    self ! StartEnsimeServer
  }

  var sbtBotRef: Option[ActorRef] = None

  override def receive: Receive = {
    // internal state message
    case StartEnsimeServer =>
      // this will send a message when the workspace initialisation is complete
      context.actorOf(EnsimeServerActor.props(workspaceRootDir), "ensimeServer")
    case SBTBotReady =>
      log.info("SBTBot ready - notifying everybody ***")
      startupRequestId.foreach { rId =>
        sendSyncResponse(rId, InitialiseWorkspaceSuccessResponse)
      }
      startupRequestId = None
      activeWorkspace = true
    case EnsimeProcessStarted =>
      log.info("Got notification of ensime server successful startup")
      ensimeServerRef = Some(sender)

      log.info("Starting SBT Bot")
      sbtBotRef = Some(context.actorOf(SBTBot.props(workspaceRootDir), "sbtBot"))
    case EnsimeProcessStartupFailed(msg) =>
      log.info(s"Got notification of ensime server failed startup: $msg")
      startupRequestId.foreach { rId =>
        sendSyncResponse(rId, InitialiseWorkspaceFailedResponse(msg))
      }
      cleardownWorkspaceFiles()
      startupRequestId = None
    case ConnectToServerCmd =>
      startWebserverConn()
    case m: Message =>
      handleServerMessage(m)
    case Terminated(termRef) =>
      if (webServerConnActor.contains(termRef)) {
        log.info("Connection to server failed - scheduling retry")
        cleardownWorkspace()
        webServerConnActor = None
        context.system.scheduler.scheduleOnce(5000.millis, self, ConnectToServerCmd)
      } else
        log.warning("Got termination on unexpected ref $termRef")
    case e: EnsimeEvent =>
      handleEnsimeEvent(e)
    case unknown =>
      log.info(s"Unknown message received - $unknown from $sender")
  }
}

object RobotActor {

  class RequestRejectedException(msg: String) extends Exception(msg)

  def props(
    robotName: String,
    host: String,
    port: Int,
    path: String,
    useSSL: Boolean,
    secret: String,
    courseName: String,
    sourceRoot: Path
  ): Props =
    Props(new RobotActor(robotName, host, port, path, useSSL, secret, courseName, sourceRoot))
}