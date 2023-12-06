package org.scalajs.cli.tests

class Tests extends munit.FunSuite {

  val launcher = sys.props.getOrElse(
    "test.scala-js-cli.path",
    sys.error("test.scala-js-cli.path Java property not set")
  )
  val scalaJsVersion = sys.props.getOrElse(
    "test.scala-js-cli.scala-js-version",
    sys.error("test.scala-js-cli.scala-js-version Java property not set")
  )

  def getScalaJsLibraryCp(cwd: os.Path) = os
    .proc(
      "cs",
      "fetch",
      "--classpath",
      "-E",
      "org.scala-lang:scala-library",
      s"org.scala-js::scalajs-library:$scalaJsVersion"
    )
    .call(cwd = cwd)
    .out
    .trim()

  def getScalaJsCompilerPlugin(cwd: os.Path) = os.proc("cs", "fetch", "--intransitive", s"org.scala-js:scalajs-compiler_2.13.6:$scalaJsVersion")
    .call(cwd = cwd).out.trim()

  test("tests") {
    val dir = os.temp.dir()
    os.write(
      dir / "foo.scala",
      """object Foo {
        |  def main(args: Array[String]): Unit = {
        |    println(s"asdf ${1 + 1}")
        |    new A
        |  }
        |
        |  class A
        |}
        |""".stripMargin
    )

    val scalaJsLibraryCp = getScalaJsLibraryCp(dir)

    os.makeDir.all(dir / "bin")
    os.proc(
      "cs",
      "launch",
      "scalac:2.13.6",
      "--",
      "-classpath",
      scalaJsLibraryCp,
      s"-Xplugin:${getScalaJsCompilerPlugin(dir)}",
      "-d",
      "bin",
      "foo.scala"
    ).call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

    val res = os
      .proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "-s",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "bin"
      )
      .call(cwd = dir, stderr = os.Pipe)
    val expectedInOutput =
      "Warning: using a single file as output (--output) is deprecated since Scala.js 1.3.0. Use --outputDir instead."
    assert(res.err.text().contains(expectedInOutput))

    val testJsSize = os.size(dir / "test.js")
    val testJsMapSize = os.size(dir / "test.js.map")
    assert(testJsSize > 0)
    assert(testJsMapSize > 0)

    val runRes = os.proc("node", "test.js").call(cwd = dir)
    val runOutput = runRes.out.trim()
    assert(runOutput == "asdf 2")

    os.makeDir.all(dir / "test-output")
    os.proc(
      launcher,
      "--stdlib",
      scalaJsLibraryCp,
      "-s",
      "--outputDir",
      "test-output",
      "--moduleSplitStyle",
      "SmallestModules",
      "--moduleKind",
      "CommonJSModule",
      "-mm",
      "Foo.main",
      "bin"
    ).call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

    val jsFileCount = os.list(dir / "test-output").count { p =>
      p.last.endsWith(".js") && os.isFile(p)
    }
    assert(jsFileCount > 1)

    val splitRunRes = os
      .proc("node", "test-output/main.js")
      .call(cwd = dir)
    val splitRunOutput = splitRunRes.out.trim()
    assert(splitRunOutput == "asdf 2")
  }

  test("fullLinkJs mode does not throw") {
    val dir = os.temp.dir()
    os.write(
      dir / "foo.scala",
      """object Foo {
        |  def main(args: Array[String]): Unit = {
        |    val s = "Hello"
        |    println("Hello" + s.charAt(5))
        |  }
        |
        |  class A
        |}
        |""".stripMargin
    )

    val scalaJsLibraryCp = getScalaJsLibraryCp(dir)

    os.makeDir.all(dir / "bin")
    os.proc(
      "cs",
      "launch",
      "scalac:2.13.6",
      "--",
      "-classpath",
      scalaJsLibraryCp,
      s"-Xplugin:${getScalaJsCompilerPlugin(dir)}",
      "-d",
      "bin",
      "foo.scala"
    ).call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

    val res = os
      .proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "-s",
        "--fullOpt",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "bin"
      )
      .call(cwd = dir, mergeErrIntoOut = true)

    val testJsSize = os.size(dir / "test.js")
    val testJsMapSize = os.size(dir / "test.js.map")
    assert(testJsSize > 0)
    assert(testJsMapSize > 0)

    os.proc("node", "test.js").call(cwd = dir, check = true)
  }

  test("fastLinkJs mode throws") {
    val dir = os.temp.dir()
    os.write(
      dir / "foo.scala",
      """object Foo {
        |  def main(args: Array[String]): Unit = {
        |    val s = "Hello"
        |    println("Hello" + s.charAt(5))
        |  }
        |
        |  class A
        |}
        |""".stripMargin
    )

    val scalaJsLibraryCp = getScalaJsLibraryCp(dir)

    os.makeDir.all(dir / "bin")
    os.proc(
      "cs",
      "launch",
      "scalac:2.13.6",
      "--",
      "-classpath",
      scalaJsLibraryCp,
      s"-Xplugin:${getScalaJsCompilerPlugin(dir)}",
      "-d",
      "bin",
      "foo.scala"
    ).call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

    os
      .proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "--fastOpt",
        "-s",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "bin"
      )
      .call(cwd = dir, mergeErrIntoOut = true)

    val testJsSize = os.size(dir / "test.js")
    val testJsMapSize = os.size(dir / "test.js.map")
    assert(testJsSize > 0)
    assert(testJsMapSize > 0)

    val runRes = os.proc("node", "test.js").call(cwd = dir, check = false, stderr = os.Pipe)
    assert(runRes.exitCode == 1)

    assert(runRes.err.trim().contains("UndefinedBehaviorError"))
  }
}
