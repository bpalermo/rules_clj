# rules_clj

Bazel rules for Clojure.

> **Status: 0.2.2, in the [Bazel Central Registry](https://registry.bazel.build/modules/rules_clj).**
> Compilation, tests, binaries, REPLs, `deps.edn` dependencies, BUILD generation,
> ClojureScript, GraalVM native images and Maven publishing all work, on Bazel 8 and 9
> across Linux and macOS. `bazel_dep(name = "rules_clj", version = "0.2.2")` is all a
> consumer needs — protoc-gen-clojure, clj-protobuf and clj-grpc build with exactly that.
>
> **New in 0.2.0:** `clj_maven_export` publishes to Clojars from Bazel, so a library no
> longer needs a `build.clj` and a second build system to write a pom and make some HTTP
> requests.
>
> It is early. Everything documented is implemented and tested, and nothing has been
> used in anger by anyone but its author, so expect the API to move where real use finds it
> wrong.

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
| `clj_maven_export` | A publishable jar and its pom, plus a `bazel run` target that uploads them to Clojars. No `build.clj`, no second toolchain. |
| `//tools/lock` | Resolves your `deps.edn` once into a lockfile. Builds then fetch by digest — no Clojure CLI, no `~/.m2`, no resolution at build time. |
| `//tools/tidy` | Writes the BUILD files your `ns` forms imply, and `--mode=check` fails when they drift. |
| `//tools/pom_gen` | Derives a pom from a `deps.edn` — the action behind `clj_maven_export`, runnable by hand while working on one. |

## Using it

```starlark
# MODULE.bazel
bazel_dep(name = "rules_clj", version = "0.2.2")
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

## Publishing

A Clojure library that builds with Bazel usually still carries a `build.clj`, a `:build`
alias and a second toolchain, for the sole purpose of writing a pom and making some HTTP
requests. `clj_maven_export` replaces all of it:

```starlark
load("@rules_clj//clojure:defs.bzl", "clj_maven_export")

clj_maven_export(
    name = "clj-grpc",
    srcs = glob(["src/**/*.clj"]),
    artifact_id = "clj-grpc",
    deps_edn = "deps.edn",
    group_id = "com.github.bpalermo",
    license = ("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    scm_url = "https://github.com/bpalermo/clj-grpc",
    strip_prefix = "src",
    version_file = "version.edn",
)
```

```sh
bazel build //:clj-grpc                       # the jar and the pom
bazel run //:clj-grpc.publish -- --dry-run    # print every PUT, send nothing
bazel run //:clj-grpc.publish                 # upload
```

Two targets, because publishing is two jobs that most tools run as one:

| | |
|---|---|
| `bazel build //:clj-grpc` | Hermetic, cacheable, safe to run anywhere. A pure function of the source tree, so **run it in CI** — it tells you the pom is well-formed and the jar packs, months before anyone tries to cut a release. |
| `bazel run //:clj-grpc.publish` | Not hermetic, not cacheable, not idempotent — Clojars refuses a second attempt at a version, because releases there are immutable. Tagged `manual`, so `bazel build //...` never touches it. The same shape as `oci_push`. |

The pom's `<dependencies>` are the `deps.edn`'s **top-level** `:deps`, emitted verbatim:
same versions, same `$classifier` suffixes, same `:exclusions`, no resolution. Alias deps
are invisible, so a test runner never becomes a consumer's problem. That verbatim rule is
not laziness — a library that pins its transitive graph by hand (clj-grpc pins fourteen
Netty artifacts to hold gRPC and Netty in alignment) needs the pom to say what it asked
for, not what one machine's resolver answered. `//tests/maven` checks the generated pom
against the pom clj-grpc actually deployed to Clojars, dependency for dependency.

The version lives in a **file** rather than an attribute, read by an action rather than
during analysis. Bumping it re-runs the two actions that consume it — the pom generator,
and the packaging step that embeds the pom in the jar — while the rest of the build, and
the whole analysis phase, is untouched. An attribute would instead make every version bump
a graph change. The file a release workflow already reads is the file the pom comes from.

Credentials are read by the publisher process from `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` (falling back to `MAVEN_USER` and `MAVEN_PASSWORD`) at the moment of
the request, and never by Bazel. A secret that reaches an action's environment reaches
its cache key and its execution log too. On Clojars the password must be a deploy token.

To exercise the whole path without publishing anything, install into a local repository:

```sh
bazel run //:clj-grpc.publish -- --repository=file://"$HOME"/.m2/repository
```

Being honest about the edges: the jar holds **sources**, not classes — a Clojure library
compiles in its consumer's process, and shipping AOT classes freezes protocols and records
against the versions present when you built. There is no GPG signing (Clojars does not
require it, and Maven Central needs considerably more than a signature), and no
`maven-metadata.xml` is written, which release versions do not need.

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
