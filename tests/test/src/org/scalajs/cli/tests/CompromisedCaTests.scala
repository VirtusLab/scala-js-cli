package org.scalajs.cli.tests

import org.scalajs.cli.securityprobe.TlsCheck
import org.scalajs.cli.securityprobe.TlsCheck.Result

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.*
import scala.util.Properties

/** Verifies that the compromised intermediate CAs (CVE-2026-91403) aren't trusted by:
  *   - the JVM running the tests (the one Mill runs on, see `mill-jvm-version` in `build.mill`),
  *   - the JVM from `JAVA_HOME` / `PATH` (the one set up on the CI, running the JVM launcher),
  *   - a GraalVM native image built with the same settings as the scala-js-cli native launchers,
  *     which embeds the trust store it was built with (see
  *     `ScalaJsCliNativeImageBase.nativeImageTrustStoreDir` in `build.mill`).
  *
  * Also checks that the native launchers under test don't embed any compromised CA certificate.
  *
  * See https://www.scala-lang.org/blog/jdk-certificate-vulnerability.html
  */
class CompromisedCaTests extends munit.FunSuite {
  override def munitTimeout: Duration = 5.minutes

  private def probeClassPath   = sys.props.get("test.security-probe.classpath")
  private def probeNative      = sys.props.get("test.security-probe.native-path")
  private def launcherIsNative =
    sys.props.get("test.scala-js-cli.native").contains("true")
  private def launcher = sys.props.get("test.scala-js-cli.path")

  /** Subjects of the compromised CAs, mirroring
    * https://github.com/eed3si9n/emudhra-emsign-check/blob/main/bin/distrust-emudhra.sh
    */
  private val compromisedCaSubjects = Seq("emSign", "AAA Certificate Services")

  private def externalJava: String = {
    val exe = if Properties.isWin then "java.exe" else "java"
    sys.env.get("JAVA_HOME").map(h => (os.Path(h) / "bin" / exe).toString).getOrElse("java")
  }

  private def assertNotTrusted(description: String)(check: String => Result): Unit = {
    // if TLS doesn't work at all (e.g. no network, an intercepting proxy, a broken trust store),
    // the compromised hosts would get rejected too, which proves nothing
    check(TlsCheck.controlUrl) match {
      case Result.Trusted(_) =>
      case other => fail(s"TLS handshake with the control host failed ($description): $other")
    }
    val failures = TlsCheck.compromisedUrls.flatMap { url =>
      check(url) match {
        case Result.Untrusted       => None
        case Result.Trusted(status) => Some(s"VULNERABLE (HTTP $status) $url")
        // DNS, timeouts, expired certificates, protocol errors etc. say nothing about the CAs
        case Result.Error(e) => Some(s"ERROR ($e) $url")
      }
    }
    assert(
      failures.isEmpty,
      s"""Compromised CAs are trusted, or an unexpected error was encountered ($description):
         |${failures.mkString(System.lineSeparator())}""".stripMargin
    )
  }

  /** Runs the probe once for all the URLs, as starting it is costly */
  private def probeChecker(command: Seq[String]): String => Result = {
    val urls    = TlsCheck.controlUrl +: TlsCheck.compromisedUrls
    val output  = os.proc(command, "tls", urls).call(stderr = os.Pipe, check = false)
    val results = output.out.lines().flatMap { line =>
      line.split("\t", 2) match {
        case Array(url, result) => Some(url -> Result.parse(result))
        case _                  => None
      }
    }.toMap
    url =>
      results.getOrElse(
        url,
        Result.Error(
          s"No result from the probe (exit code ${output.exitCode}): ${output.err.text().trim}"
        )
      )
  }

  private def javaInfo(java: String): String =
    os.proc(java, "-XshowSettings:properties", "-version")
      .call(stderr = os.Pipe, mergeErrIntoOut = true, check = false)
      .out.lines()
      .map(_.trim)
      .filter(l =>
        l.startsWith("java.home") || l.startsWith("java.vendor.version") ||
        l.startsWith("java.runtime.version")
      )
      .mkString(", ")

  test("the JVM running the tests does not trust the compromised CAs") {
    assertNotTrusted(
      s"test JVM: ${sys.props("java.home")}, ${sys.props("java.vendor")} " +
        s"${sys.props("java.runtime.version")}"
    )(TlsCheck.check)
  }

  test("the JVM from JAVA_HOME / PATH does not trust the compromised CAs") {
    val cp   = probeClassPath.getOrElse(fail("test.security-probe.classpath Java property not set"))
    val java = externalJava
    assertNotTrusted(s"external JVM: ${javaInfo(java)}")(
      probeChecker(Seq(java, "-cp", cp, "org.scalajs.cli.securityprobe.SecurityProbe"))
    )
  }

  test("a native image built like the launchers does not trust the compromised CAs") {
    assume(probeNative.nonEmpty, "native image probe not built (only built for native tests)")
    assertNotTrusted(s"native image: ${probeNative.get}")(probeChecker(probeNative.toSeq))
  }

  test("the native launcher does not embed the compromised CAs") {
    assume(launcherIsNative, "not testing a native launcher")
    val path = os.Path(launcher.getOrElse(fail("test.scala-js-cli.path Java property not set")))
    // The launchers don't do any TLS, so no certificate gets embedded in them (their trust store
    // is stripped of the compromised CAs anyway, see the native image probe test above).
    // Certificates end up in the image heap in their DER encoding, in which subjects are plain
    // ASCII. Note that sun.security.util.AnchorCertificates (initialized at build time, used for
    // the jdkCA algorithm constraints, not as trust anchors) embeds the certificates of the
    // GraalVM's own cacerts if TLS becomes reachable, which would make this test fail too.
    val content = new String(os.read.bytes(path), StandardCharsets.ISO_8859_1)
    val found   = compromisedCaSubjects.filter(content.contains)
    assert(found.isEmpty, s"Compromised CAs embedded in $path: ${found.mkString(", ")}")
  }
}
