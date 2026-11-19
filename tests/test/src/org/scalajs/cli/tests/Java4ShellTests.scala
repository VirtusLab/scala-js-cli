package org.scalajs.cli.tests

import org.scalajs.cli.securityprobe.Java4ShellCheck

import scala.util.Properties

/** Verifies that the JVMs used to build and test scala-js-cli aren't affected by CVE-2026-114938
  * ("Java4Shell"):
  *   - the JVM running the tests (the one Mill runs on, see `mill-jvm-version` in `build.mill`),
  *   - the JVM from `JAVA_HOME` / `PATH` (the one set up on the CI, running the JVM launcher).
  *
  * The native launchers aren't affected, as GraalVM native images don't embed the HotSpot JIT. The
  * scala-js-cli code and its dependencies don't use `java.util.Base64` either.
  *
  * See https://www.scala-lang.org/blog/jdk-java4shell-vulnerability.html
  */
class Java4ShellTests extends munit.FunSuite {

  private def probeClassPath = sys.props.get("test.security-probe.classpath")

  private def externalJava: String = {
    val exe = if Properties.isWin then "java.exe" else "java"
    sys.env.get("JAVA_HOME").map(h => (os.Path(h) / "bin" / exe).toString).getOrElse("java")
  }

  private def assertNotVulnerable(description: String, status: Java4ShellCheck.Status): Unit =
    assert(
      !status.isVulnerable,
      s"JVM vulnerable to CVE-2026-114938 ($description): version ${status.version}, " +
        s"UseBASE64Intrinsics: ${status.base64Intrinsics.fold("unknown")(_.toString)}"
    )

  test("the affected versions are detected") {
    def affected(v: String) = Java4ShellCheck.isAffectedVersion(Runtime.Version.parse(v))
    assert(!affected("11.0.33"))
    assert(!affected("17.0.5"))
    assert(affected("17.0.6"))
    assert(affected("17.0.21+9"))
    assert(!affected("17.0.21.1+2"))
    assert(affected("21.0.13+11"))
    assert(!affected("21.0.13.1+2"))
    assert(affected("23.0.2+7"))
    assert(affected("25.0.3+9"))
    assert(affected("25.0.5+10"))
    assert(!affected("25.0.5.1+2"))
    assert(!affected("25.0.6"))
    assert(affected("26.0.2"))
    assert(!affected("27.0.1.1+4"))
  }

  test("the JVM running the tests is not vulnerable to Java4Shell") {
    assertNotVulnerable(
      s"test JVM: ${sys.props("java.home")}, ${sys.props("java.vendor")}",
      Java4ShellCheck.current()
    )
  }

  test("the JVM from JAVA_HOME / PATH is not vulnerable to Java4Shell") {
    val cp   = probeClassPath.getOrElse(fail("test.security-probe.classpath Java property not set"))
    val java = externalJava
    val res  = os.proc(java, "-cp", cp, "org.scalajs.cli.securityprobe.SecurityProbe", "java4shell")
      .call(stderr = os.Pipe, check = false)
    val status = Java4ShellCheck.Status.parse(res.out.text()).getOrElse {
      fail(
        s"Unexpected probe output (exit code ${res.exitCode}): ${res.out.text()}${res.err.text()}"
      )
    }
    assertNotVulnerable(s"external JVM: $java", status)
  }
}
