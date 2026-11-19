package org.scalajs.cli.securityprobe

import java.io.IOException
import java.net.URI
import java.security.cert.{CertPathBuilderException, CertPathValidatorException}
import javax.net.ssl.HttpsURLConnection

/** Checks whether the trust store of the current runtime (a JVM, or a GraalVM native image, which
  * embeds the trust store it was built with) accepts the certificate chain of an HTTPS host.
  */
object TlsCheck {
  enum Result {
    // the TLS handshake succeeded, so the certificate chain was accepted
    case Trusted(status: Int)
    // either no trust anchor for the chain ("PKIX path building failed", e.g. the CAs got removed
    // from the trust store), or a certificate of the chain is explicitly distrusted by the JDK
    // ("Untrusted certificate", what the patched JDKs do)
    case Untrusted
    case Error(message: String)

    def render: String = this match {
      case Trusted(status) => s"TRUSTED $status"
      case Untrusted       => "UNTRUSTED"
      case Error(message)  => s"ERROR ${message.replaceAll("\\s+", " ")}"
    }
  }

  object Result {
    def parse(s: String): Result =
      s.split(" ", 2).toList match {
        case "TRUSTED" :: status :: Nil => Trusted(status.toInt)
        case "UNTRUSTED" :: Nil         => Untrusted
        case "ERROR" :: message :: Nil  => Error(message)
        case _                          => Error(s"Unexpected probe output: $s")
      }
  }

  /** A host with a legitimate certificate chain, which must be trusted */
  val controlUrl = "https://repo1.maven.org/maven2/"

  /** Hosts with certificates issued by the compromised intermediate CAs (CVE-2026-91403), see
    * https://www.scala-lang.org/blog/jdk-certificate-vulnerability.html
    */
  val compromisedUrls: List[String] =
    for {
      validation <- List("dv", "ov", "ev")
      aaa        <- List("", "-aaa")
      generation <- List("g3b", "g3c")
    } yield s"https://$validation-$generation$aaa-emudhra.we-have-a-problem.com/"

  def check(url: String): Result = {
    val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpsURLConnection]
    try {
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(15000)
      Result.Trusted(conn.getResponseCode)
    }
    catch {
      case e: IOException =>
        // the certificate path exception is wrapped, so walk the causes
        val causes = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null)
        if causes.exists(isUntrusted) then Result.Untrusted
        else Result.Error(e.toString)
    }
    finally conn.disconnect()
  }

  private def isUntrusted(t: Throwable): Boolean =
    t match {
      // no trust anchor for the chain
      case _: CertPathBuilderException => true
      // explicitly distrusted, other validation failures (expired certificates etc.) say nothing
      // about the CAs
      case e: CertPathValidatorException =>
        Option(e.getMessage).exists(_.startsWith("Untrusted certificate"))
      case _ => false
    }
}
