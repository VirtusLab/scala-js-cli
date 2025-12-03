//| mvnDeps:
//| - io.github.alexarchambault.mill::mill-native-image::0.2.3
//| - io.github.alexarchambault.mill::mill-native-image-upload:0.2.3
//| - com.goyeau::mill-scalafix::0.6.0
//| - com.lumidion::sonatype-central-client-requests:0.6.0
//| - io.get-coursier:coursier-launcher_2.13:2.1.25-M19
package build

import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill.*
import mill.api.{BuildCtx, Task}
import mill.javalib.testrunner.TestResult
import mill.scalalib.*
import mill.util.{Tasks, VcsVersion}

import scala.annotation.unused
import scala.concurrent.duration.*
import scala.util.Properties.isWin
import com.goyeau.mill.scalafix.ScalafixModule
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}

object Versions {
  def scala213                = "2.13.18"
  def scalaJsVersion          = "1.20.1"
  def jsoniterVersion         = "2.38.5"
  def scalaJsImportMapVersion = "0.1.1"
  def graalVmVersion          = "22.3.1"
  def munitVersion            = "1.2.1"
  def osLibVersion            = "0.11.6"
  def pprintVersion           = "0.9.5"
  def coursierVersion         = "2.1.25-M19"
  def scoptVersion            = "4.1.0"
  def ubuntuVersion           = "24.04"
}

trait ScalaJsCliModule extends ScalaModule with ScalafixModule {
  override def scalacOptions: T[Seq[String]] =
    super.scalacOptions() ++ Seq("-Wunused", "-deprecation")

  def scalaVersion: T[String] = Versions.scala213
}

object cli extends Cli

trait Cli extends ScalaJsCliModule with ScalaJsCliPublishModule {
  def artifactName: T[String] = "scalajs" + super.artifactName()

  def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
    mvn"org.scala-js::scalajs-linker:${Versions.scalaJsVersion}",
    mvn"com.github.scopt::scopt:${Versions.scoptVersion}",
    mvn"com.lihaoyi::os-lib:${Versions.osLibVersion}",
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterVersion}", // This is the java8 version of jsoniter, according to scala-cli build
    mvn"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterVersion}", // This is the java8 version of jsoniter, according to scala-cli build
    mvn"com.armanbilge::scalajs-importmap:${Versions.scalaJsImportMapVersion}"
  )

  def mainClass: T[Option[String]] = Some("org.scalajs.cli.Scalajsld")

  def transitiveJars: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleDeps)(_.jar)()
  }

  def jarClassPath: T[Seq[PathRef]] = Task {
    val cp = runClasspath() ++ transitiveJars()
    cp.filter(ref => os.exists(ref.path) && !os.isDir(ref.path))
  }

  def standaloneLauncher: T[PathRef] = Task {
    val cachePath = os.Path(coursier.cache.FileCache().location, BuildCtx.workspaceRoot)

    def urlOf(path: os.Path): Option[String] =
      if path.startsWith(cachePath) then {
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

    val dest = Task.ctx().dest / (if isWin then "launcher.bat" else "launcher")

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
    val params        = Parameters.Bootstrap(Seq(loaderContent), mainClass0)
      .withDeterministic(true)
      .withPreamble(preamble)

    BootstrapGenerator.generate(params, dest.toNIO)

    PathRef(dest)
  }
}

trait ScalaJsCliNativeImage extends ScalaJsCliModule with NativeImage {

  def nativeImageClassPath: T[Seq[PathRef]] = Task {
    runClasspath()
  }

  def nativeImageOptions: T[Seq[String]] = Task {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "-H:IncludeResources=org/scalajs/linker/backend/emitter/.*.sjsir",
      "-H:IncludeResources=com/google/javascript/jscomp/js/polyfills.txt",
      "-H:IncludeResourceBundles=com.google.javascript.jscomp.parsing.ParserConfig"
    )
  }

  def nativeImagePersist: Boolean = System.getenv("CI") != null

  def graalVmVersion: String = Versions.graalVmVersion

  def nativeImageGraalVmJvmId: T[String] = s"graalvm-java17:$graalVmVersion"

  def nativeImageName: T[String] = "scala-js-ld"

  def moduleDeps: Seq[JavaModule] = Seq(cli)

  def compileMvnDeps: T[Seq[Dep]] =
    super.compileMvnDeps() ++ Seq(mvn"org.graalvm.nativeimage:svm:$graalVmVersion")

  def nativeImageMainClass: T[String] = "org.scalajs.cli.Scalajsld"

  def nameSuffix = ""

  @unused
  def copyToArtifacts(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val _ = Upload.copyLauncher0(
      nativeLauncher = nativeImage().path,
      directory = directory,
      name = "scala-js-ld",
      compress = true,
      workspace = BuildCtx.workspaceRoot,
      suffix = nameSuffix
    )
  }
}

object native extends ScalaJsCliNativeImage

def native0: native.type = native

def csVersion: String = Versions.coursierVersion

trait ScalaJsCliStaticNativeImage extends ScalaJsCliNativeImage {
  def nameSuffix = "-static"

  def buildHelperImage: T[Unit] = Task {
    os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
      .call(cwd = BuildCtx.workspaceRoot / "musl-image", stdout = os.Inherit)
    ()
  }

  def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Task {
    buildHelperImage()
    Some(
      NativeImage.linuxStaticParams(
        dockerImage = "scala-cli-base-musl:latest",
        csUrl =
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

  def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Some(
    NativeImage.linuxMostlyStaticParams(
      dockerImage = s"ubuntu:${Versions.ubuntuVersion}",
      csUrl =
        s"https://github.com/coursier/coursier/releases/download/v$csVersion/cs-x86_64-pc-linux.gz"
    )
  )
}

object `native-mostly-static` extends ScalaJsCliMostlyStaticNativeImage

@unused
object tests extends ScalaJsCliModule {

  @unused
  object test extends ScalaTests with TestModule.Munit {
    def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ Seq(
      mvn"org.scalameta::munit:${Versions.munitVersion}",
      mvn"com.lihaoyi::os-lib:${Versions.osLibVersion}",
      mvn"com.lihaoyi::pprint:${Versions.pprintVersion}"
    )

    override def testForked(args: String*): Command[(msg: String, results: Seq[TestResult])] =
      jvm(args*)

    private def testExtraArgs(launcher: os.Path): Seq[String] = Seq(
      s"-Dtest.scala-js-cli.path=$launcher",
      s"-Dtest.scala-js-cli.scala-js-version=${Versions.scalaJsVersion}"
    )

    @unused
    def jvm(args: String*): Command[(msg: String, results: Seq[TestResult])] = Task.Command {
      testTask(
        args = Task.Anon(args ++ testExtraArgs(cli.standaloneLauncher().path)),
        globSelectors = Task.Anon(Seq.empty[String])
      )()
    }

    @unused
    def native(args: String*): Command[(msg: String, results: Seq[TestResult])] = Task.Command {
      testTask(
        args = Task.Anon(args ++ testExtraArgs(native0.nativeImage().path)),
        globSelectors = Task.Anon(Seq.empty[String])
      )()
    }

    @unused
    def nativeStatic(args: String*): Command[(msg: String, results: Seq[TestResult])] =
      Task.Command {
        testTask(
          args = Task.Anon(args ++ testExtraArgs(`native-static`.nativeImage().path)),
          globSelectors = Task.Anon(Seq.empty[String])
        )()
      }

    @unused
    def nativeMostlyStatic(args: String*): Command[(msg: String, results: Seq[TestResult])] =
      Task.Command {
        testTask(
          args = Task.Anon(args ++ testExtraArgs(`native-mostly-static`.nativeImage().path)),
          globSelectors = Task.Anon(Seq.empty[String])
        )()
      }
  }
}

def ghOrg      = "virtuslab"
def ghName     = "scala-js-cli"
def publishOrg = "org.virtuslab.scala-cli"

trait ScalaJsCliPublishModule extends SonatypeCentralPublishModule {
  import mill.scalalib.publish.*

  def pomSettings: T[PomSettings] = PomSettings(
    description = artifactName(),
    organization = publishOrg,
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

  def publishVersion: T[String] = finalPublishVersion()
}

def computePublishVersion(state: VcsVersion.State, simple: Boolean): String =
  if state.commitsSinceLastTag > 0 then
    if simple then {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .filter(_ != "nightly")
        .map(_.stripPrefix("v"))
        .map(_.takeWhile(c => c == '.' || c.isDigit))
        .flatMap { tag =>
          if simple then {
            val idx = tag.lastIndexOf(".")
            if idx >= 0 then
              Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
            else None
          }
          else {
            val idx = tag.indexOf("-")
            if idx >= 0 then Some(tag.take(idx) + "+" + tag.drop(idx + 1) + "-SNAPSHOT") else None
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
      if idx >= 0 then rawVersion.take(idx) + "-" + rawVersion.drop(idx + 1) + "-SNAPSHOT"
      else rawVersion
    }
  else
    state.lastTag
      .getOrElse(state.format())
      .stripPrefix("v")

def finalPublishVersion: T[String] = {
  val isCI = System.getenv("CI") != null
  if isCI then
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
  def publishSonatype(tasks: Tasks[PublishModule.PublishData]): Command[Unit] =
    Task.Command {
      val publishVersion = finalPublishVersion
      System.err.println(s"Publish version: $publishVersion")
      val bundleName = s"$publishOrg-$ghName-$publishVersion"
      System.err.println(s"Publishing bundle: $bundleName")
      publishSonatype0(
        data = Task.sequence(tasks.value)(),
        log = Task.ctx().log,
        workspace = BuildCtx.workspaceRoot,
        env = Task.env,
        bundleName = bundleName
      )
    }

  private def publishSonatype0(
    data: Seq[PublishModule.PublishData],
    log: mill.api.Logger,
    workspace: os.Path,
    env: Map[String, String],
    bundleName: String
  ): Unit = {
    val credentials = SonatypeCredentials(
      username = sys.env("SONATYPE_USERNAME"),
      password = sys.env("SONATYPE_PASSWORD")
    )
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
    val publisher = new SonatypeCentralPublisher(
      credentials = credentials,
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
      awaitTimeout = timeout.toMillis.toInt
    )
    val publishingType = if isRelease then PublishingType.AUTOMATIC else PublishingType.USER_MANAGED
    val finalBundleName = if bundleName.nonEmpty then Some(bundleName) else None
    publisher.publishAll(
      publishingType = publishingType,
      singleBundleName = finalBundleName,
      artifacts = artifacts*
    )
  }

  @unused
  def upload(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val version = finalPublishVersion()

    val path      = os.Path(directory, BuildCtx.workspaceRoot)
    val launchers = os.list(path).filter(os.isFile(_)).map { path =>
      path -> path.last
    }
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if version.endsWith("-SNAPSHOT") then ("launchers", true) else ("v" + version, false)

    Upload.upload(
      ghOrg = ghOrg,
      ghProj = ghName,
      ghToken = ghToken,
      tag = tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers*)
    // when we release `0.13.0.1` we should also update native launchers in tag `0.13.0`
    if version != Versions.scalaJsVersion && !version.endsWith("-SNAPSHOT") then
      Upload.upload(
        ghOrg = ghOrg,
        ghProj = ghName,
        ghToken = ghToken,
        tag = s"v${Versions.scalaJsVersion}",
        dryRun = false,
        overwrite = true
      )(launchers*)
  }
}
