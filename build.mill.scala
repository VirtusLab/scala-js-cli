import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.31-1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.31-1`
import $ivy.`io.get-coursier::coursier-launcher:2.1.24`
import $ivy.`com.goyeau::mill-scalafix::0.5.1`
import build_.package_.native0
import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.define.Task
import mill.scalalib._
import mill.testrunner.TestResult
import org.jgrapht.graph.DefaultGraphType.simple

import scala.annotation.unused
import scala.concurrent.duration._
import scala.util.Properties.isWin
import com.goyeau.mill.scalafix.ScalafixModule

object Versions {
  def scala213                = "2.13.16"
  def scalaJsVersion          = "1.19.0"
  def jsoniterVersion         = "2.35.3"
  def scalaJsImportMapVersion = "0.1.1"
  def graalVmVersion          = "22.3.1"
  def munitVersion            = "1.1.1"
  def osLibVersion            = "0.11.4"
  def pprintVersion           = "0.9.0"
  def coursierVersion         = "2.1.24"
  def scoptVersion            = "4.1.0"
  def ubuntuVersion           = "24.04"
}
trait ScalaJsCliModule extends ScalaModule with ScalafixModule {
  override def scalacOptions: Target[Seq[String]] = super.scalacOptions.map(_ ++ Seq("-Wunused"))
  def scalaVersion: Target[String]                = Versions.scala213
}

object cli extends Cli
trait Cli extends ScalaJsCliModule with ScalaJsCliPublishModule {
  def artifactName: Target[String] = "scalajs" + super.artifactName()
  def ivyDeps: Target[Agg[Dep]] = super.ivyDeps() ++ Seq(
    ivy"org.scala-js::scalajs-linker:${Versions.scalaJsVersion}",
    ivy"com.github.scopt::scopt:${Versions.scoptVersion}",
    ivy"com.lihaoyi::os-lib:${Versions.osLibVersion}",
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterVersion}", // This is the java8 version of jsoniter, according to scala-cli build
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterVersion}", // This is the java8 version of jsoniter, according to scala-cli build
    ivy"com.armanbilge::scalajs-importmap:${Versions.scalaJsImportMapVersion}"
  )
  def mainClass: Target[Option[String]] = Some("org.scalajs.cli.Scalajsld")

  def transitiveJars: Target[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleDeps)(_.jar)()
  }

  def jarClassPath: Target[Seq[PathRef]] = Task {
    val cp = runClasspath() ++ transitiveJars()
    cp.filter(ref => os.exists(ref.path) && !os.isDir(ref.path))
  }

  def standaloneLauncher: Target[PathRef] = Task {
    val cachePath = os.Path(coursier.cache.FileCache().location, Task.workspace)

    def urlOf(path: os.Path): Option[String] =
      if (path.startsWith(cachePath)) {
        val segments = path.relativeTo(cachePath).segments
        val url      = segments.head + "://" + segments.tail.mkString("/")
        Some(url)
      }
      else None

    import coursier.launcher.{
      BootstrapGenerator,
      ClassPathEntry,
      Parameters,
      Preamble
    }
    val cp         = jarClassPath().map(_.path)
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))

    val dest = Task.ctx().dest / (if (isWin) "launcher.bat" else "launcher")

    val preamble = Preamble()
      .withOsKind(isWin)
      .callsItself(isWin)
    val entries = cp.map { path =>
      urlOf(path) match {
        case None =>
          val content = os.read.bytes(path)
          val name    = path.last
          ClassPathEntry.Resource(name, os.mtime(path), content)
        case Some(url) => ClassPathEntry.Url(url)
      }
    }
    val loaderContent = coursier.launcher.ClassLoaderContent(entries)
    val params = Parameters.Bootstrap(Seq(loaderContent), mainClass0)
      .withDeterministic(true)
      .withPreamble(preamble)

    BootstrapGenerator.generate(params, dest.toNIO)

    PathRef(dest)
  }
}

trait ScalaJsCliNativeImage extends ScalaJsCliModule with NativeImage {

  def nativeImageClassPath: Target[Seq[PathRef]] = Task {
    runClasspath()
  }
  def nativeImageOptions: Target[Seq[String]] = Target {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "-H:IncludeResources=org/scalajs/linker/backend/emitter/.*.sjsir",
      "-H:IncludeResources=com/google/javascript/jscomp/js/polyfills.txt",
      "-H:IncludeResourceBundles=com.google.javascript.jscomp.parsing.ParserConfig"
    )
  }
  def nativeImagePersist: Boolean             = System.getenv("CI") != null
  def graalVmVersion: String                  = Versions.graalVmVersion
  def nativeImageGraalVmJvmId: Target[String] = s"graalvm-java17:$graalVmVersion"
  def nativeImageName: Target[String]         = "scala-js-ld"
  def moduleDeps: Seq[JavaModule]             = Seq(cli)
  def compileIvyDeps: Target[Agg[Dep]] =
    super.compileIvyDeps() ++ Seq(ivy"org.graalvm.nativeimage:svm:$graalVmVersion")
  def nativeImageMainClass: Target[String] = "org.scalajs.cli.Scalajsld"

  def nameSuffix = ""
  @unused
  def copyToArtifacts(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val _ = Upload.copyLauncher0(
      nativeImage().path,
      directory,
      s"scala-js-ld",
      compress = true,
      workspace = Task.workspace,
      suffix = nameSuffix
    )
  }
}

object native extends ScalaJsCliNativeImage

def native0: native.type = native

def csVersion: String = Versions.coursierVersion

trait ScalaJsCliStaticNativeImage extends ScalaJsCliNativeImage {
  def nameSuffix = "-static"
  def buildHelperImage: Target[Unit] = Task {
    os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
      .call(cwd = Task.workspace / "musl-image", stdout = os.Inherit)
    ()
  }
  def nativeImageDockerParams: Target[Option[NativeImage.DockerParams]] = Task {
    buildHelperImage()
    Some(
      NativeImage.linuxStaticParams(
        "scala-cli-base-musl:latest",
        s"https://github.com/coursier/coursier/releases/download/v$csVersion/cs-x86_64-pc-linux.gz"
      )
    )
  }
  def writeNativeImageScript(scriptDest: String, imageDest: String = ""): Command[Unit] =
    Task.Command {
      buildHelperImage()
      super.writeNativeImageScript(scriptDest, imageDest)()
    }
}
object `native-static` extends ScalaJsCliStaticNativeImage

trait ScalaJsCliMostlyStaticNativeImage extends ScalaJsCliNativeImage {
  def nameSuffix = "-mostly-static"
  def nativeImageDockerParams: Target[Option[NativeImage.DockerParams]] = Some(
    NativeImage.linuxMostlyStaticParams(
      s"ubuntu:${Versions.ubuntuVersion}",
      s"https://github.com/coursier/coursier/releases/download/v$csVersion/cs-x86_64-pc-linux.gz"
    )
  )
}
object `native-mostly-static` extends ScalaJsCliMostlyStaticNativeImage

@unused
object tests extends ScalaJsCliModule {

  @unused
  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps: Target[Agg[Dep]] = super.ivyDeps() ++ Seq(
      ivy"org.scalameta::munit:${Versions.munitVersion}",
      ivy"com.lihaoyi::os-lib:${Versions.osLibVersion}",
      ivy"com.lihaoyi::pprint:${Versions.pprintVersion}"
    )

    override def test(args: String*): Command[(String, Seq[TestResult])] = jvm(args: _*)

    private def testExtraArgs(launcher: os.Path): Seq[String] = Seq(
      s"-Dtest.scala-js-cli.path=$launcher",
      s"-Dtest.scala-js-cli.scala-js-version=${Versions.scalaJsVersion}"
    )

    @unused
    def jvm(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
      testTask(
        Task.Anon(args ++ testExtraArgs(cli.standaloneLauncher().path)),
        Task.Anon(Seq.empty[String])
      )()
    }
    @unused
    def native(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
      testTask(
        Task.Anon(args ++ testExtraArgs(native0.nativeImage().path)),
        Task.Anon(Seq.empty[String])
      )()
    }
    @unused
    def nativeStatic(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
      testTask(
        Task.Anon(args ++ testExtraArgs(`native-static`.nativeImage().path)),
        Task.Anon(Seq.empty[String])
      )()
    }
    @unused
    def nativeMostlyStatic(args: String*): Command[(String, Seq[TestResult])] = Task.Command {
      testTask(
        Task.Anon(args ++ testExtraArgs(`native-mostly-static`.nativeImage().path)),
        Task.Anon(Seq.empty[String])
      )()
    }
  }
}

def ghOrg  = "virtuslab"
def ghName = "scala-js-cli"
trait ScalaJsCliPublishModule extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings: Target[PomSettings] = PomSettings(
    description = artifactName(),
    organization = "org.virtuslab.scala-cli",
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault"),
      Developer("sjrd", "Sébastien Doeraene", "https://github.com/sjrd"),
      Developer("gzm0", "Tobias Schlatter", "https://github.com/gzm0"),
      Developer("nicolasstucki", "Nicolas Stucki", "https://github.com/nicolasstucki")
    )
  )
  def publishVersion: Target[String] = finalPublishVersion()
}

def computePublishVersion(state: VcsState, simple: Boolean): String =
  if (state.commitsSinceLastTag > 0)
    if (simple) {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .filter(_ != "nightly")
        .map(_.stripPrefix("v"))
        .map(_.takeWhile(c => c == '.' || c.isDigit))
        .flatMap { tag =>
          if (simple) {
            val idx = tag.lastIndexOf(".")
            if (idx >= 0)
              Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
            else
              None
          }
          else {
            val idx = tag.indexOf("-")
            if (idx >= 0) Some(tag.take(idx) + "+" + tag.drop(idx + 1) + "-SNAPSHOT")
            else None
          }
        }
        .getOrElse("0.0.1-SNAPSHOT")
      Some(versionOrEmpty)
        .filter(_.nonEmpty)
        .getOrElse(state.format())
    }
    else {
      val rawVersion = os
        .proc("git", "describe", "--tags")
        .call()
        .out
        .text()
        .trim
        .stripPrefix("v")
        .replace("latest", "0.0.0")
        .replace("nightly", "0.0.0")
      val idx = rawVersion.indexOf("-")
      if (idx >= 0) rawVersion.take(idx) + "-" + rawVersion.drop(idx + 1) + "-SNAPSHOT"
      else rawVersion
    }
  else
    state.lastTag
      .getOrElse(state.format())
      .stripPrefix("v")

def finalPublishVersion: Target[String] = {
  val isCI = System.getenv("CI") != null
  if (isCI)
    Task(persistent = true) {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = false)
    }
  else
    Task {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = true)
    }
}

object ci extends Module {
  @unused
  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]): Command[Unit] =
    Task.Command {
      publishSonatype0(
        data = define.Target.sequence(tasks.value)(),
        log = Task.ctx().log,
        workspace = Task.workspace,
        env = Task.env
      )
    }

  private def publishSonatype0(
    data: Seq[PublishModule.PublishData],
    log: mill.api.Logger,
    workspace: os.Path,
    env: Map[String, String]
  ): Unit = {

    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSPHRASE")
    val timeout     = 10.minutes

    val artifacts = data.map { case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set      = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
      )
      set.head
    }
    val publisher = new scalalib.publish.SonatypePublisher(
      uri = "https://oss.sonatype.org/service/local",
      snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
      credentials = credentials,
      signed = true,
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode",
        "loopback",
        "--passphrase",
        pgpPassword,
        "--armor",
        "--use-agent"
      ),
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      workspace = workspace,
      env = env,
      awaitTimeout = timeout.toMillis.toInt,
      stagingRelease = isRelease
    )

    publisher.publishAll(isRelease, artifacts: _*)
  }
  @unused
  def upload(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val version = finalPublishVersion()

    val path = os.Path(directory, Task.workspace)
    val launchers = os.list(path).filter(os.isFile(_)).map { path =>
      path -> path.last
    }
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("launchers", true)
      else ("v" + version, false)

    Upload.upload(
      ghOrg,
      ghName,
      ghToken,
      tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers: _*)
    if (
      version != Versions.scalaJsVersion && !version.endsWith("-SNAPSHOT")
    ) // when we release `0.13.0.1` we should also update native launchers in tag `0.13.0`
      Upload.upload(
        ghOrg,
        ghName,
        ghToken,
        s"v${Versions.scalaJsVersion}",
        dryRun = false,
        overwrite = true
      )(launchers: _*)
  }
}
