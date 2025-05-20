package org.scalajs.cli.internal

import com.armanbilge.sjsimportmap.ImportMappedIRFile
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.scalajs.linker.interface.IRFile

import java.io.File

object ImportMapJsonIr {

  type Scope = Map[String, String]

  final case class ImportMap(
    val imports: Map[String, String],
    val scopes: Option[Map[String, Scope]]
  )

  object ImportMap {
    implicit val codec: JsonValueCodec[ImportMap] = JsonCodecMaker.make
  }

  def remapImports(pathToImportPath: File, irFiles: Seq[IRFile]): Seq[IRFile] = {
    val path = os.Path(pathToImportPath)
    val importMapJson = if (os.exists(path))
      readFromString[ImportMap](os.read(path))
    else
      throw new AssertionError(s"importmap file at path $path does not exist.")
    if (importMapJson.scopes.nonEmpty)
      throw new AssertionError("importmap scopes are not supported.")
    val importsOnly: Map[String, String] = importMapJson.imports

    val remapFct = importsOnly.toSeq.foldLeft((in: String) => in) { case (fct, (s1, s2)) =>
      val fct2: (String => String) = in => in.replace(s1, s2)
      in => fct(fct2(in))
    }

    irFiles.map(ImportMappedIRFile.fromIRFile(_)(remapFct))
  }
}
