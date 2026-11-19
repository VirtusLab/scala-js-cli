package org.scalajs.cli.securityprobe

/** Test-only probe, run by the tests on JVMs other than the one running the tests, and as a GraalVM
  * native image built with the same settings as the scala-js-cli native launchers.
  *
  * Not published, and not part of the scala-js-cli launchers.
  */
object SecurityProbe {
  def main(args: Array[String]): Unit =
    args.toList match {
      case "tls" :: urls =>
        for url <- urls do println(s"$url\t${TlsCheck.check(url).render}")
      case _ =>
        System.err.println("Usage: security-probe tls <url>...")
        sys.exit(1)
    }
}
