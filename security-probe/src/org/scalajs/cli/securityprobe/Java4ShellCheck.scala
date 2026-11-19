package org.scalajs.cli.securityprobe

import com.sun.management.HotSpotDiagnosticMXBean

import java.lang.management.ManagementFactory

import scala.util.Try

/** Checks whether the current JVM is affected by CVE-2026-114938 ("Java4Shell"), a heap buffer
  * overflow in the AVX-512 Base64 decoder intrinsic of the HotSpot JIT.
  *
  * A JVM is considered safe when it is a release with the fix, or when the Base64 intrinsics are
  * disabled (`-XX:-UseBASE64Intrinsics`). The CPU isn't taken into account (only x86-64 CPUs with
  * AVX-512 VBMI are affected), so that the checks give the same results on all machines.
  *
  * Doesn't apply to GraalVM native images, which don't embed the HotSpot JIT.
  *
  * See https://www.scala-lang.org/blog/jdk-java4shell-vulnerability.html
  */
object Java4ShellCheck {

  /** The first affected version, per feature release (JDK 8 and 11 aren't affected) */
  private val firstAffected = Runtime.Version.parse("17.0.6")

  /** The first fixed version, per feature release. Other feature releases (18-20, 22-24, 26) are
    * end-of-life, and don't get the fix.
    */
  private val firstFixed: Map[Int, Runtime.Version] =
    Seq("17.0.21.1", "21.0.13.1", "25.0.5.1", "27.0.1.1")
      .map(Runtime.Version.parse)
      .map(v => v.feature -> v)
      .toMap

  def isAffectedVersion(version: Runtime.Version): Boolean =
    version.compareToIgnoreOptional(firstAffected) >= 0 &&
    firstFixed.get(version.feature).forall(version.compareToIgnoreOptional(_) < 0)

  /** Whether the Base64 intrinsics are enabled on the current JVM, if that can be determined */
  def base64IntrinsicsEnabled(): Option[Boolean] =
    Try {
      ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])
        .getVMOption("UseBASE64Intrinsics")
        .getValue
        .toBoolean
    }.toOption

  final case class Status(version: Runtime.Version, base64Intrinsics: Option[Boolean]) {
    def isVulnerable: Boolean = isAffectedVersion(version) && !base64Intrinsics.contains(false)
    def render: String        = s"$version\t${base64Intrinsics.fold("unknown")(_.toString)}"
  }

  object Status {
    def parse(s: String): Option[Status] =
      s.trim.split("\t") match {
        case Array(version, intrinsics) =>
          Try(Runtime.Version.parse(version)).toOption.map { v =>
            Status(v, intrinsics.toBooleanOption)
          }
        case _ => None
      }
  }

  def current(): Status = Status(Runtime.version(), base64IntrinsicsEnabled())
}
