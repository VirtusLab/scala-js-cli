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

  def getScalaJsCompilerPlugin(cwd: os.Path) = os.proc("cs", "fetch", "--intransitive", s"org.scala-js:scalajs-compiler_2.13.14:$scalaJsVersion")
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
      "scalac:2.13.14",
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

  test("longRunning") {
    val dir = os.temp.dir()
    def writePrintlnMain(stringToPrint: String) = {
      os.write.over(
        dir / "foo.scala",
        s"""object Foo {
          |  def main(args: Array[String]): Unit = {
          |    println("$stringToPrint")
          |  }
          |}
          |""".stripMargin
      )
    }

    val scalaJsLibraryCp = getScalaJsLibraryCp(dir)

    os.makeDir.all(dir / "bin")

    def compile() = {
      val compileCommand = os.proc(
        "cs",
        "launch",
        "scalac:2.13.14",
        "--",
        "-classpath",
        scalaJsLibraryCp,
        s"-Xplugin:${getScalaJsCompilerPlugin(dir)}",
        "-d",
        "bin",
        "foo.scala"
      )
      compileCommand.call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)
    }

    val command = os
      .proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "-s",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "--longRunning",
        "bin",
      )

    writePrintlnMain("first version")
    compile()
    val process = command.spawn(cwd = dir)

    def waitForLinkingToFinish() = {
      while({
        val line = process.stdout.readLine()
        assert(line != null, "Got null from reading stdout")
        line != "SCALA_JS_LINKING_DONE"
      }) {}
    }

    try {
      locally {
        waitForLinkingToFinish()
        val testJsSize = os.size(dir / "test.js")
        val testJsMapSize = os.size(dir / "test.js.map")
        assert(testJsSize > 0)
        assert(testJsMapSize > 0)

        val runRes = os.proc("node", "test.js").call(cwd = dir)
        val runOutput = runRes.out.trim()
        assert(runOutput == "first version")
      }

      writePrintlnMain("second version")
      compile()

      // trigger new linking
      process.stdin.writeLine("")
      process.stdin.flush()

      waitForLinkingToFinish()

      locally {
        val testJsSize = os.size(dir / "test.js")
        val testJsMapSize = os.size(dir / "test.js.map")
        assert(testJsSize > 0)
        assert(testJsMapSize > 0)

        val runRes = os.proc("node", "test.js").call(cwd = dir)
        val runOutput = runRes.out.trim()
        assertEquals(runOutput, "second version")
      }

      // close stdin to allow the process to terminate gracefully
      process.stdin.close()

      // wait some time for the process to terminate
      Thread.sleep(100)

      assert(!process.isAlive(), "process did not terminate gracefully")
    } finally {

      process.close()
    }
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
      "scalac:2.13.14",
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


  test("using fullLinkJS with ES modules succeeds") {
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
      "scalac:2.13.14",
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
        "--moduleKind",
        "ESModule",
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
      "scalac:2.13.14",
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

  test("import map") {
    val dir = os.temp.dir()
    os.write(
      dir / "foo.scala",
      """
        |import scala.scalajs.js
        |import scala.scalajs.js.annotation.JSImport
        |import scala.scalajs.js.typedarray.Float64Array
        |
        |object Foo {
        |  def main(args: Array[String]): Unit = {
        |     println(linspace(-10.0, 10.0, 10))
        |  }
        |}
        |
        |@js.native
        |@JSImport("@stdlib/linspace", JSImport.Default)
        |object linspace extends js.Object {
        |  def apply(start: Double, stop: Double, num: Int): Float64Array = js.native
        |}
        |""".stripMargin
    )

    val scalaJsLibraryCp = getScalaJsLibraryCp(dir)

    os.makeDir.all(dir / "bin")
    os.proc(
      "cs",
      "launch",
      "scalac:2.13.14",
      "--",
      "-classpath",
      scalaJsLibraryCp,
      s"-Xplugin:${getScalaJsCompilerPlugin(dir)}",
      "-d",
      "bin",
      "foo.scala"
    ).call(cwd = dir, stdin = os.Inherit, stdout = os.Inherit)

    val notThereYet = dir / "no-worky.json"
    val launcherRes = os.proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "--fastOpt",
        "-s",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "bin",
        "--importmap",
        notThereYet
      )
      .call(cwd = dir, mergeErrIntoOut = true)

    assert(launcherRes.exitCode == 0) // as far as I can tell launcher returns code 0 for failed validation?
    assert(launcherRes.out.trim().contains(s"importmap file at path ${notThereYet} does not exist"))

    os.write(notThereYet, "...")

    val failToParse = os.proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "--fastOpt",
        "-s",
        "-o",
        "test.js",
        "-mm",
        "Foo.main",
        "bin",
        "--importmap",
        notThereYet
      )
      .call(cwd = dir, check = false, mergeErrIntoOut = true, stderr = os.Pipe)

    assert(failToParse.out.text().contains("com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException"))

    val importmap = dir / "importmap.json"
    val substTo = "https://cdn.jsdelivr.net/gh/stdlib-js/array-base-linspace@esm/index.mjs"
    os.write(importmap, s"""{ "imports": {"@stdlib/linspace":"$substTo"}}""")

    val out = os.makeDir.all(dir / "out")

    val worky = os.proc(
        launcher,
        "--stdlib",
        scalaJsLibraryCp,
        "--fastOpt",
        "-s",
        "--outputDir",
        "out",
        "-mm",
        "Foo.main",
        "bin",
        "--moduleKind",
        "ESModule",
        "--importmap",
        importmap
      )
      .call(cwd = dir, check = false, mergeErrIntoOut = true, stderr = os.Pipe)
    os.write( dir / "out" / "index.html", """<html><head><script type="module" src="main.js"></script></head><body></body></html>""")

    // You can serve the HTML file here and check the console output of the index.html file, hosted in a simple webserver to prove the concept
    //println(dir)
    assert(os.exists(dir / "out" / "main.js"))
    val rawJs = os.read.lines(dir / "out" / "main.js")
    assert(rawJs(1).contains(substTo))
  }
}
