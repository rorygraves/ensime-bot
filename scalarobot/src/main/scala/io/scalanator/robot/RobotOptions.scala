package io.scalanator.robot

import java.io.File

/**
 * Basic Robot configuration.
 * @param workDir The working directory for the server
 */
case class RobotOptions(
  robotName: String = "",
  serverHost: String = "UNKNOWN",
  serverPort: Int = -1,
  serverURLPath: String = "/robotConn/",
  serverUseSSL: Boolean = false,
  workDir: File = new File("/tmp/undefined"),
  courseName: String = "",
  secret: String = ""
)

object RobotOptions {
  val parser = new scopt.OptionParser[RobotOptions]("Scalanator Robot") {
    opt[String]("robotName") required () valueName "name" action { (x, c) =>
      c.copy(robotName = x)
    } text "The name of this robot"
    opt[String]("courseName") required () valueName "courseName" action { (x, c) =>
      c.copy(courseName = x)
    } text "The course this robot is to host (shortName)"
    opt[String]("serverHost") required () valueName "address" action { (x, c) =>
      c.copy(serverHost = x)
    } text "The host address of the server instance"
    opt[Int]("serverPort") required () valueName "port" action { (x, c) =>
      c.copy(serverPort = x)
    } text "The host port of the server instance"
    opt[String]("serverURLPath") valueName "urlPath" action { (x, c) =>
      c.copy(serverURLPath = x)
    } text "The host port of the server instance"
    opt[String]("secret") required () valueName "<sharedSecret>" action { (x, c) =>
      c.copy(secret = x)
    }
    opt[Boolean]("serverUseSSL") valueName "<boolean>" action { (x, c) =>
      c.copy(serverUseSSL = x)
    }
    opt[File]("workDir") required () valueName "<workDir>" action { (x, c) =>
      c.copy(workDir = x)
    } text "The work directory this process uses" validate { dir =>
      if (dir.exists() && !dir.isDirectory)
        Left("Work directory must be a directory")
      else
        Right(())
    }
  }

  def parse(args: Array[String]): Option[RobotOptions] = {
    parser.parse(args, RobotOptions())
  }
}