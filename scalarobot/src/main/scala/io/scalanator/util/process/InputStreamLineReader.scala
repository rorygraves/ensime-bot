package io.scalanator.util.process

import java.io._

import akka.util.ByteString

import scala.annotation.tailrec
import scala.util.Try

class InputStreamLineReader(inputStream: InputStream, name: String) {
  var bs: ByteString = ByteString()
  val bis = new BufferedInputStream(inputStream)

  var limit = 2048
  val byteBuffer = new Array[Byte](limit)

  var isClosed = false

  def close(): Option[String] = {
    val res = if (isClosed)
      None
    else {
      val remainingOutput = bs.decodeString("UTF-8")
      if (remainingOutput.length > 0)
        Some(remainingOutput)
      else
        None
    }

    bs = ByteString.empty
    res
  }

  def read(): List[String] = {
    if (isClosed)
      Nil
    else {
      val rawRes = performRead(Nil)
      rawRes.reverse
    }
  }

  @tailrec
  private final def performRead(cur: List[String]): List[String] = {
    val actualRead = if (bis.available > 0)
      bis.read(byteBuffer)
    else
      0
    if (actualRead == -1) {
      Try(inputStream.close())
      isClosed = true
      val remainingOutput = bs.decodeString("UTF-8")
      if (remainingOutput.length > 0) {
        remainingOutput :: cur
      } else
        cur
    } else if (actualRead > 0) {
      val byteString = ByteString.fromArray(byteBuffer, 0, actualRead)
      bs ++= byteString

      var activeCurrent = cur
      while (bs.contains('\n')) {
        val (init, rem) = bs.span(_ != '\n')
        val lineStr = init.decodeString("UTF-8")
        activeCurrent ::= lineStr
        bs = rem.drop(1)
      }
      performRead(activeCurrent)
    } else {
      cur
    }
  }
}