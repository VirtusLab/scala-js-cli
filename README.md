# Command Line Interface for Scala.js

`scalajs-cli` provides a Command Line Interface (CLI) to the Scala.js compiler
and linker.

Until Scala.js 1.0.0-M2, the CLI is part of the
[core repository of Scala.js](https://github.com/scala-js/scala-js).
This repository contains the CLI for later versions.


# GraalVM

Mainly meant to be used by [Scala CLI](https://github.com/VirtusLab/scala-cli) (see [`VirtusLab/scala-cli#676`](https://github.com/VirtusLab/scala-cli/pull/676))

# Development

The build depends on git submodules, so make sure they're checked out before building:

```bash
git clone --recurse-submodules https://github.com/VirtusLab/scala-js-cli.git
# or, in an existing clone
git submodule update --init
```

## Mill plugins built from source

The [mill-native-image](https://github.com/alexarchambault/mill-native-image) Mill plugin (and its
`mill-native-image-upload` companion) is not pulled as a published artifact. Instead, its sources live in the
`mill-native-image/` git submodule and are compiled by the Mill meta-build (`mill-build/build.mill`).
The meta-build also declares the other Mill plugin dependencies of the build, reusing the dependencies
declared in `project/deps/package.mill`.

To bump it, check out the desired commit in the submodule and commit the updated submodule pointer:

```bash
git -C mill-native-image fetch origin
git -C mill-native-image checkout <commit>
git add mill-native-image
```
