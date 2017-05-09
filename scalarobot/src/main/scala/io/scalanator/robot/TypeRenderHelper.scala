package io.scalanator.robot

import io.scalanator.robotapi.{ Info, Warning, Error, CompilerNote }
import org.ensime.api._

object TypeRenderHelper {
  def sigToString(sig: CompletionSignature): String = {

    val paramBlocks = sig.sections.map { section =>
      "(" + section.map(_._2).mkString(",") + ")"
    }.mkString

    paramBlocks + ": " + sig.result
  }

  def renderTypeInfo(ti: TypeInfo): String = {
    ti match {
      case ArrowTypeInfo(name, fullName, resultType, params: Iterable[ParamSectionInfo]) =>
        val paramsString = params.map(renderParamsSection).mkString("")
        val resultTypeStr = renderTypeInfo(resultType)
        s"$paramsString => $resultTypeStr"
      case BasicTypeInfo(tName, _, _, typeArgs, _, _) =>
        s"$tName${renderTypeArgs(typeArgs)}"
    }
  }

  def renderTypeArgs(args: Iterable[TypeInfo]): String = {
    if (args.isEmpty)
      ""
    else
      args.map(renderTypeInfo).mkString("[", ",", "]")
  }

  def renderParamsSection(ps: ParamSectionInfo): String = {
    val implicitStr = if (ps.isImplicit) "implicit " else ""
    val mappedTypes = ps.params.map { case (pName, info) => s"$implicitStr$pName: ${renderTypeInfo(info)}" }
    mappedTypes.mkString("(", "", ")")
  }

  def toCompilerNote(note: Note): CompilerNote = {
    val sev = note.severity match {
      case NoteError => Error
      case NoteWarn => Warning
      case NoteInfo => Info
    }

    CompilerNote(note.file, note.msg, sev, note.beg, note.end, note.line, note.col)
  }

}
