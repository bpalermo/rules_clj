# rules_clj

Bazel rules for Clojure.

> **Status: phase 2 complete, plus the worker.** `clj_library` compiles ahead of time by default,
> one namespace per target, through a persistent worker, with a conformance suite covering
> protocols, `deftype`, macros, `data_readers` and mixed compiled/interpreted code. Nothing is published to the Bazel Central Registry, and the
> version is deliberately `0.0.0` so that nothing can depend on it by accident.

## Why another one

Clojure's ahead-of-time compilation is transitive: compiling one namespace compiles everything
it requires. Bazel wants the opposite — one target, one unit of work, one output. Reconciling
those is the entire job of a Clojure ruleset, and it is worth doing carefully.

The existing options are [simuons/rules_clojure](https://github.com/simuons/rules_clojure),
untouched since 2021, and [griffinbank/rules_clojure](https://github.com/griffinbank/rules_clojure),
which works but carries the weight of the shape it grew into. This is an independent
implementation that makes four different structural choices, each of which removes a category
of code rather than adding one:

| | |
|---|---|
| The compiler is a **Java shim** with no third-party dependencies | It shares a classpath with your code, so it must bring nothing to conflict with. Also means the ruleset never has to compile itself to build itself. |
| Analysis runs in **its own process** | Which is why nothing needs vendoring: tools.namespace and tools.deps are ordinary dependencies over there. |
| **A worker that shares a JVM but not a Clojure runtime** | 0.398s per compile becomes 0.265s. Each request still loads its own Clojure in its own classloader — sharing that is where order-dependent compilation bugs come from. The class data sharing archive tried first is off by default: being uncacheable, it costs every action its place in the action cache. |
| `deps.edn` resolved into a **checked-in lockfile** | Builds are hermetic and work offline; no Clojure CLI download, no `~/.m2`. |

The reasoning behind each is in [`docs/design.md`](docs/design.md) — including the one that was
stated as a falsifiable bet and then falsified by its own benchmark.

Scope includes **GraalVM native binaries** (`clj_native_binary`). A Clojure CLI or protoc plugin
pays JVM startup on every invocation, so producing a native image is part of shipping Clojure
rather than an afterthought — and it is the rule with the most sharp edges: closed-world
analysis makes AOT a correctness requirement rather than an optimisation, the final link is not
hermetic because it is the platform's, and GraalVM does not cross-compile. The design document
says so plainly instead of finding out later.

## What it does

| | |
|---|---|
| `clj_library` | Compiles a namespace ahead of time, or packages it as source. One namespace per target, so one target's jar holds one target's work. |
| `clj_binary`, `clj_test`, `clj_repl` | Run it. Tests report per-`deftest` results as JUnit XML, so a failure names the test rather than the target. |
| `cljs_library` | ClojureScript through `cljs.main`. |
| `clj_native_binary` | A GraalVM native image. The example starts in 0.01s against 0.28s for the same program on a JVM. |
| `//tools/lock` | Resolves your `deps.edn` once into a lockfile. Builds then fetch by digest — no Clojure CLI, no `~/.m2`, no resolution at build time. |
| `//tools/tidy` | Writes the BUILD files your `ns` forms imply, and `--mode=check` fails when they drift. |

## Using it

```starlark
# MODULE.bazel
bazel_dep(name = "rules_clj", version = "0.0.0")
```

```starlark
# src/example/BUILD.bazel
load("@rules_clj//clojure:defs.bzl", "clj_binary", "clj_library", "clj_test")

clj_library(
    name = "greeting",
    srcs = ["greeting.clj"],
    namespaces = ["example.greeting"],
    strip_prefix = "src",   # so example.greeting resolves to example/greeting.clj
)

clj_binary(
    name = "hello",
    main = "example.greeting",
    deps = [":greeting"],
)

clj_test(
    name = "greeting_test",
    ns = "example.greeting-test",
    deps = [":greeting_test_lib"],
)
```

A Clojure runtime comes with the rules — three jars pinned by digest, registered as a
toolchain, no Maven resolver involved. Override it by registering your own `clj_toolchain`.

`clj_test` runs one namespace per target, and writes a JUnit XML report so a failure names the
`deftest` and the line rather than just the target. See [`examples/hello`](examples/hello) for a
module that builds, runs and tests.

## Development

```sh
bazel test //...                   # everything, including formatting
bazel run //bazel/dev:format       # rewrite Starlark in place
```

Requires JDK 21; the build selects a hermetic `remotejdk_21` for both target and tool JVMs.

```sh
tools/benchmark/benchmark.sh 40          # what a compile costs, and what the worker saves
bazel build //... --@rules_clj//clojure:worker=false   # a JVM per action, for debugging
```

## Licence

Apache-2.0 — see [`LICENSE`](LICENSE). Copyright 2026 Bruno Palermo.

This is not a fork; see [`NOTICE`](NOTICE) for the statement of independence and for credit to
the prior art that shaped the question.
