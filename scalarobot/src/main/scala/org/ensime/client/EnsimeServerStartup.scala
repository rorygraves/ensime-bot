package org.ensime.client

import akka.actor.ActorSystem
import ammonite.ops._
import io.scalanator.util.process.ProcessCommand
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util.Try

class EnsimeServerStartup(actorSystem: ActorSystem, val projectRoot: Path, memoryConfig: MemoryConfig)(implicit val ec: ExecutionContext) {

  val logger = LoggerFactory.getLogger("EnsimeServer")

  val dotEnsimeFile = projectRoot / ".ensime"
  val cacheDir = projectRoot / ".ensime_cache"

  val httpPortFile = cacheDir / "http"
  val tcpPortFile = cacheDir / "port"

  val resolutionDir = cacheDir / "Resolution"

  val resolutionProjectDir = resolutionDir / "project"
  val resolutionSBTFile = resolutionDir / "build.sbt"
  val classpathFile = resolutionDir / "classpath"
  val resolutionBuildPropertiesFile = resolutionProjectDir / "build.properties"

  val scalaVersion = "2.11.8"
  val ensimeVersion = "1.0.0"
  def projectBuildProps = "sbt.version=0.13.11\n"

  def readHttpPort: Option[Int] = {
    if (exists ! httpPortFile) {
      Try {
        val contents = read ! httpPortFile
        Integer.parseInt(contents.trim)
      }.toOption
    } else
      None
  }

  def saveClasspathCommand: ProcessCommand = {
    ProcessCommand(
      List("sbt", "-Dsbt.log.noformat=true", "saveClasspath"),
      workingDir = Some(resolutionDir)
    )
  }

  def genEnsimeCommand: ProcessCommand = {
    ProcessCommand(
      List("sbt", "-Dsbt.log.noformat=true", "ensimeConfig"),
      workingDir = Some(projectRoot)
    )
  }

  def serverCommand: ProcessCommand = {

    logger.info("Starting ensime server")

    val javaHome = sys.env.get("JAVA_HOME")
    val toolsJar = javaHome match {
      case Some(path) =>
        val toolsJarPath = Path(path) / "lib" / "tools.jar"
        if (!exists(toolsJarPath))
          throw new IllegalArgumentException(s"Cannot resolve tools jar from JAVA_HOME - expecting $toolsJarPath")
        toolsJarPath
      case None =>
        throw new IllegalStateException("JAVA_HOME not set")
    }

    val logbackConfigPath = cacheDir / "ensime-logback.xml"
    write.over(logbackConfigPath, """<configuration>
      |  <contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator">
      |    <resetJUL>true</resetJUL>
      |  </contextListener>
      |  <!-- Incompatible with akka? https://groups.google.com/d/msg/akka-user/YVri58taWsM/X6-XR0_i1nwJ -->
      |  <!-- <turboFilter class="ch.qos.logback.classic.turbo.DuplicateMessageFilter" /> -->
      |  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      |    <encoder>
      |      <!-- NOTE: this truncates messages -->
      |      <pattern>%d{HH:mm:ss.SSS} %-5level %X{akkaSource:-None} %logger{10} - %.-250msg%n</pattern>
      |    </encoder>
      |  </appender>
      |  <root level="INFO">
      |    <appender-ref ref="STDOUT" />
      |  </root>
      |  <logger name="org.ensime" level="INFO" />
      |  <logger name="akka" level="WARN" />
      |  <logger name="scala.tools" level="WARN" />
      |  <logger name="org.ensime.server.RichPresentationCompiler" level="WARN" />
      |</configuration>
      |""".stripMargin)

    val baseClasspath = read ! classpathFile
    val classpath = s"$toolsJar:$baseClasspath"

    val cmd = List("java", "-Densime.config=" + dotEnsimeFile,
      s"-Dlogback.configurationFile=$logbackConfigPath",
      // TODO - These should come from the .ensime file
      "-Dfile.encoding=UTF8", "-XX:+CMSClassUnloadingEnabled",
      s"-XX:MaxPermSize=${memoryConfig.maxPermSizeMb}m",
      s"-XX:ReservedCodeCacheSize=${memoryConfig.reservedCodeCacheSizeMb}m",
      s"-Xms${memoryConfig.minMemMb}m", s"-Xmx${memoryConfig.maxMemMb}m", s"-Xss${memoryConfig.stackSizeMb}m",
      "-classpath", classpath, "-Densime.explode.on.disconnect=true", "org.ensime.server.Server")

    ProcessCommand(cmd = cmd, workingDir = Some(cacheDir))
  }

  def mkdirs(): Unit = {
    logger.info("Creating workspace")

    mkdir ! resolutionDir
    mkdir ! resolutionProjectDir
    write.over(resolutionSBTFile, sbtClasspathScript(classpathFile))
    write.over(resolutionBuildPropertiesFile, projectBuildProps)
  }

  def sbtClasspathScript(classpathFile: Path) = s"""
     |import sbt._
     |import IO._
     |import java.io._
     |
     |scalaVersion := "$scalaVersion"
     |
     |ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }
     |
     |// we don't need jcenter, so this speeds up resolution
     |fullResolvers -= Resolver.jcenterRepo
     |
     |// allows local builds of scala
     |resolvers += Resolver.mavenLocal
     |
     |// for java support
     |resolvers += "NetBeans" at "http://bits.netbeans.org/nexus/content/groups/netbeans"
     |
     |// this is where the ensime-server snapshots are hosted
     |resolvers += Resolver.sonatypeRepo("snapshots")
     |
     |libraryDependencies += "org.ensime" %% "ensime" % "$ensimeVersion"
     |
     |dependencyOverrides ++= Set(
     |   "org.scala-lang" % "scala-compiler" % scalaVersion.value,
     |   "org.scala-lang" % "scala-library" % scalaVersion.value,
     |   "org.scala-lang" % "scala-reflect" % scalaVersion.value,
     |   "org.scala-lang" % "scalap" % scalaVersion.value
     |)
     |val saveClasspathTask = TaskKey[Unit]("saveClasspath", "Save the classpath to a file")
     |saveClasspathTask := {
     |   val managed = (managedClasspath in Runtime).value.map(_.data.getAbsolutePath)
     |   val unmanaged = (unmanagedClasspath in Runtime).value.map(_.data.getAbsolutePath)
     |   val out = file("$classpathFile")
     |   write(out, (unmanaged ++ managed).mkString(File.pathSeparator))
     |}
     |""".stripMargin
}
