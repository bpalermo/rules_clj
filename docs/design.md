# Design

This document is the specification. It is written before the implementation, and it is the
place to argue about the approach; the code is downstream of it.

## The problem

Clojure compiles. `(compile 'foo.core)` writes `foo/core.class`, `foo/core__init.class` and a
class per function, and it does so **transitively**: loading `foo.core` loads everything its
`ns` form requires, and with `*compile-files*` bound those namespaces get written out too.

That is the wrong shape for a build graph. Bazel wants one target's output to depend on, and
contain, only that target's work. If compiling `foo.core` also emits `bar.util`'s classfiles,
then:

- two targets both containing `bar.util` put two copies of the same class on one classpath, and
  which one wins is a matter of ordering;
- a change to `bar.util` invalidates every jar that happened to compile it, so incremental
  builds degrade toward full ones;
- `defprotocol` and `deftype` make this worse than untidy. They generate Java interfaces and
  classes, and the JVM considers two classes with the same name in different classloaders to be
  different types. `(instance? Foo x)` then answers false for an `x` that plainly is one.

So the ruleset's central job is **non-transitive compilation**: compile exactly one namespace
per target, and emit exactly that namespace's classfiles.

## How to compile one namespace

The mechanism is ordinary Clojure, in two steps:

1. `require` the namespace's dependencies **without** `*compile-files*` bound. They load from
   whatever their own jars provide — AOT'd classes if they were compiled, source if not — and
   nothing is written.
2. Bind `*compile-path*` to a fresh directory and `*compile-files*` to true, then `load` only
   the target namespace. Its dependencies are already in memory, so `load` does not recurse into
   them, and the compiler writes only what the target itself defines.

Then keep only the classfiles that belong to that namespace: the munged name, its `__init`, and
the `$`-suffixed classes the compiler derives from it. Anything else in the output directory is
a leak from step 2 and should fail the build loudly rather than be shipped quietly.

This ordering is the whole trick, and it puts a requirement on the rule: **anything loaded while
compiling must be a compile-time dependency**, because step 1 has to be able to resolve it.
Runtime-only dependencies do not participate. The rule surfaces that distinction rather than
hiding it.

## Three processes

Where code runs matters more than what it does, because Clojure code that shares a classpath
with the user's code can conflict with it.

| Runs | What it is | Whose classpath |
|---|---|---|
| Compilation | Java shim, reflecting into `clojure.lang.RT` | The **user's** — their Clojure version, their deps |
| Analysis (dependency resolution, BUILD generation) | Clojure, using tools.namespace and tools.deps | **Ours** — nothing of the user's |
| Test and binary execution | `java_test` / `java_binary` plus our runner | The user's runtime deps |

The first row is the constraint everything else follows from. The compiler shim shares a JVM
with user code, so it must bring **no third-party dependencies at all** — not a JSON library,
not tools.reader, not our own Clojure utilities. Written in Java, it needs only `clojure.jar`,
which the user already has.

Two things fall out of that, both of which are code we then do not write:

- **No vendoring.** A ruleset whose Clojure code shares the user's classpath has to vendor and
  rename every library it uses, or risk conflicting with the user's copy. Ours does not share,
  so the analyzer depends on tools.namespace and tools.deps the ordinary way.
- **No self-bootstrapping.** A ruleset written in Clojure has to compile itself before it can
  compile anything, which means a second, cruder build path existing only to build the first
  one. A Java shim is compiled by `java_library` like any other Java.

## Startup, and what the measurement said

Per-action JVM startup is the reason Clojure rulesets reach for persistent workers: loading
`clojure.core` costs on the order of a second, and paying it per target dominates a build.

A worker is not the only way to avoid it, and it is an expensive one — a long-lived JVM
compiling arbitrary user code needs a classloader cache, and that cache has to reason about
which classpaths are interchangeable, when a loader must be discarded because it compiled a
protocol, and what state the runtime leaked between jobs.

The alternative is to make startup cheap instead of rare. `clojure.core` ships AOT-compiled, so
its classes can go into a **class data sharing archive** mapped into every compiler JVM with
`-XX:SharedArchiveFile`. Startup drops, the process stays disposable, and every hazard above
disappears with it.

That was the bet, it was stated as falsifiable, and measuring it settled the question — though
not in the way the bet expected.

The archive works. Measured directly at the JVM, a compile action's startup drops from 0.34s to
0.245s, and `-Xshare:on` confirms the archive is genuinely mapped rather than quietly ignored.
What disqualifies it is structural. A CDS archive records the *timestamps* of the jars it was
dumped with and refuses itself when they move, so an archive restored from a cache was dumped
against timestamps that no longer hold — the JVM warns under `-Xshare:on` and says nothing under
`-Xshare:auto`. Correctness therefore requires the archive to be uncacheable, dumped in the build
that uses it. And an uncacheable input poisons everything downstream: its digest changes on every
clean build, so every compile action that consumes it changes key, and none of them can ever be
served from the action cache. Trading the action cache for a tenth of a second an action is a bad
trade at any project size.

So the archive is off by default, kept behind `--@rules_clj//clojure:cds` only so the finding
stays reproducible.

The startup cost it was meant to address is instead handled by a **persistent worker**, which
does not touch action keys. Measured as the slope between a 10-namespace and a 40-namespace
project, so that fixed build costs cancel:

| | |
|---|---|
| A JVM per action | 0.398s per compile |
| Persistent worker | 0.265s per compile (33% faster) |

The worker is deliberately modest about what it shares. It keeps the JVM and a warm JIT; it does
**not** keep a Clojure runtime. Each request loads its own, in its own classloader, and drops it
afterwards — because Clojure's loaded namespaces, protocol implementations and compiler caches
live in statics, and a runtime shared between targets carries one target's namespaces into the
next. The two-pass compile above depends on knowing exactly what is loaded: a leftover dependency
makes the pre-require a no-op, and whether a namespace gets compiled starts depending on the order
Bazel happened to schedule targets in. That is the class of bug that takes a week to find, and no
amount of startup saving is worth it. Reusing a runtime across targets is possible — it is what
gets you the rest of the way from 0.265s down — and this ruleset does not do it.

`--@rules_clj//clojure:worker=false` returns to a JVM per action, which is easier to reason about
when a compile misbehaves and is worth having for exactly that reason.

One correction worth recording, since it nearly became the conclusion: the first attempt to
measure this compared whole-build wall clock and produced numbers that were flatly wrong — a
configuration appeared to compile 40 namespaces in 4 seconds. Two causes, both worth knowing. A
build after `bazel clean` is still served by the user's disk cache unless it is disabled, so the
fastest configurations were the ones that compiled nothing. And at these sizes most of a clean
build is fixed cost — the shim, the worker launcher, the JDK's own setup — not the compiles. The
figures above are slopes between two project sizes with the caches off, which cancels both.

## Dependencies

`deps.edn` is how Clojure projects declare dependencies, and a ruleset that ignores it is a
ruleset people convert their project to rather than adopt.

Resolution happens **once, offline of the build**: `bazel run //:lock` runs tools.deps as a
library in the analyzer JVM and writes a lockfile of jar coordinates, URLs and SHA-256 digests.
A module extension reads that lockfile and creates one `http_file` and one `java_import` per
jar. Builds then need no Clojure CLI, no `~/.m2`, and no network beyond Bazel's own fetching,
and two checkouts of the same commit resolve identically by construction rather than by luck.

The lockfile is checked in. It is the record of what the project actually depends on, and it
belongs in review alongside the change that alters it.

## Hermeticity and reproducibility

Two properties, often conflated. A build is *hermetic* when its result does not depend on the
machine it ran on; it is *reproducible* when the same inputs produce the same bytes. This ruleset
treats both as requirements rather than aspirations, and the places where they do not hold are
named rather than glossed.

### What the build does not depend on

| | |
|---|---|
| The Clojure on your machine | The runtime is three jars pinned by SHA-256 and fetched by Bazel (`clojure/extensions.bzl`). There is no `clj` on the path of any action. |
| Your JDK | Actions use the JDK Bazel provides. The repository pins `remotejdk_21` for target and tool alike, and examples do the same. |
| `~/.m2` | Nothing reads it. Dependencies are fetched by digest from the URLs the lockfile records. |
| `~/.clojure/deps.edn` | Excluded when a lock is generated (`:user nil`). Leaving it in is a real trap: the first lock written here carried the author's nREPL, OpenTelemetry and Kotlin stdlib into a project that had asked for one JSON library. |
| Dependency resolution | Happens once, when someone runs `//tools/lock`, and never during a build. A build with `--nofetch` succeeds. |
| Build order | Each compile loads its own Clojure runtime in its own classloader, so no target can observe what another target loaded. |

### What is reproducible, and how it was checked

Jars are written by `Jars.java` with sorted entries and fixed timestamps, because directory
iteration order and the current time are the two easiest ways to make identical inputs produce
different bytes. Verified rather than assumed: building `//tests/conformance/src/conformance:protocols`
twice with `bazel clean` in between produces the same SHA-256, and so does building it through the
persistent worker versus a JVM per action.

That last one is worth stating plainly, because it is where a Clojure build usually stops being
reproducible. Clojure names anonymous functions with a counter that lives in the runtime, so
compiling the same namespace into a runtime that has already compiled something else yields
different class names. Compiling each target in a fresh runtime makes the counter start from the
same place every time — the isolation the worker section argues for on correctness grounds turns
out to be what makes the output deterministic too.

### Where it does not hold

- **Native images** (`clj_native_binary`, phase 5b). `native-image` shells out to the platform's
  linker, so that action runs unsandboxed with the ambient environment. Everything up to the final
  link is under Bazel's control; the link is not, and no amount of wishing changes it.
- **The class data sharing archive**, when enabled. It embeds jar timestamps, which is precisely
  why it must not be cached and why it is off by default.
- **Timestamps inside dependency jars.** Third-party jars are byte-identical because they are
  pinned by digest, but what is inside them is whoever built them's business.


## Public API

One load path — `@rules_clj//clojure:defs.bzl` — and everything else is private.

```starlark
clj_library(name, srcs, deps, runtime_deps, aot, resources, ...)
clj_binary(name, main, deps, ...)
clj_test(name, ns, deps, ...)
clj_repl(name, deps, dirs, ...)
clj_native_binary(name, binary_name, jar, config, extra_args, ...)
clj_maven_export(name, group_id, artifact_id, version_file, deps_edn, srcs, ...)
```

Two deliberate departures from what a Clojure ruleset usually looks like:

- **A real toolchain.** `//clojure:toolchain_type` carries the Clojure runtime and the compiler
  shim, so the Clojure version is a configuration choice — selectable per platform, overridable
  per build — rather than something baked into the ruleset's own dependencies.
- **A `ClojureInfo` provider** alongside `JavaInfo`, recording which namespaces a target
  provides, its sources, and whether it was AOT'd. Downstream rules and the BUILD generator then
  read the graph instead of re-parsing source to rediscover it.

## Native compilation

A Clojure program that has to *start* — a CLI, a protoc or buf plugin, a lambda — pays the
JVM's startup cost on every invocation, and for a plugin invoked once per file that is the
difference between usable and not. GraalVM's `native-image` is the answer the ecosystem has
settled on, so `clj_native_binary` is in scope rather than something users bolt on with shell
scripts around the build.

It is also the rule with the most to say, because almost nothing about it is the happy path.

### What it needs from the rest of the ruleset

Native compilation is where AOT stops being an optimisation and becomes a correctness
requirement. `native-image` performs closed-world analysis over bytecode: there is no compiler
in the produced binary, so a namespace that would be compiled from source at runtime simply
cannot exist. Concretely, the ruleset must be able to guarantee that

- every reachable namespace is AOT-compiled into the jar, with no `.clj` left to be loaded
  lazily — the same non-transitive compilation described above, applied transitively at the
  binary;
- the entry point is a real class (`:gen-class`), not a `-main` resolved through `clojure.main`;
- Clojure's static initialisers run at build time (`--initialize-at-build-time`), because they
  are what construct the namespaces the binary will use;
- `--no-fallback` is the default, so that an image which *could not* be fully analysed fails
  the build instead of silently producing a binary that ships a JVM inside it.

The last one matters most: the fallback image works, which is exactly why it is dangerous. A
rule that allows it by default turns a build error into a mysterious 200MB artifact.

### What it cannot be

**Not hermetic.** `native-image` shells out to the platform toolchain to link the final binary —
`clang`/`ld` on macOS, `gcc` on Linux — and a scrubbed action environment leaves it unable to
find one. The action therefore runs `local`, `no-sandbox`, `no-remote`, with the ambient
environment. That is a real cost and it is stated here rather than discovered later; the
alternative is a rule that does not work.

**Not routed through apple_support** on macOS. `apple_support.run` puts `SDKROOT` into the
action environment and Bazel's own `XcodeLocalEnvProvider` then injects it again; Bazel does not
merge, it crashes — *Multiple entries with same key: SDKROOT*. The rule must invoke the launcher
directly and touch none of it.

**Not cross-compiled.** GraalVM builds for the host and only the host, so a release covering
four platforms is four machines. The ruleset's job is to make each of those a normal
`bazel build`; assembling them into one release is the release workflow's problem, not the
rule's.

**Not statically linked everywhere.** `--static --libc=musl` produces a binary with no libc
dependency at all, which is the difference between running on Alpine and not. It is x86_64
only: `native-image` demands `x86_64-linux-musl-gcc` by name even when running on arm64 with the
aarch64 toolchain present, so an arm64 static build cannot be produced at all today. The rule
exposes this as an opt-in flag rather than pretending the platforms are symmetric, and the
resulting glibc floor of the dynamic builds is a property worth asserting in CI rather than
inheriting from whatever runner image built it.

### Where GraalVM comes from

[`sgammon/rules_graalvm`](https://github.com/sgammon/rules_graalvm) is the obvious answer and is
a healthy project — actively maintained, and its v0.12.0 (July 2026) declares
`bazel_compatibility = [">=7.0.0"]` and works on Bazel 9. This is not a case of routing around
something abandoned.

It is a case of routing around a **distribution** gap, and the two halves of the problem have
different answers.

**Fetching the SDK — ours, for now.** The BCR's newest `rules_graalvm` is 0.11.1 from January
2024, which does not load on Bazel 9 (`name 'JavaInfo' is not defined`). A 0.12.0 entry was
opened as bazelbuild/bazel-central-registry#9753 in July 2026 and closed without merging. Since
a BCR module may depend only on modules that are themselves in the BCR, depending on
`rules_graalvm` today would make `rules_clj` unpublishable; a `git_override` of it would make it
unpublishable *and* usable only from a root module, which is precisely the trap this ruleset
exists partly to avoid.

So `rules_clj` provisions the SDK itself: a repository rule that downloads a pinned GraalVM
build per platform by SHA, and a `//clojure:native_toolchain_type` that carries it. Two details
are not obvious — the whole SDK tree has to be an action input, because the `native-image`
launcher is a symlink into `lib/svm/bin` and resolves its `JAVA_HOME` relative to its own
location; and registering GraalVM must not substitute it as the build's Java toolchain, which is
a different thing entirely from having it on disk.

**The exit condition is explicit**: if `rules_graalvm` ≥ 0.12.0 lands in the BCR, delete our
repository rule and depend on it. Nothing else in the design assumes ownership of the download,
and helping that entry get published is a cheaper contribution to the ecosystem than maintaining
a second GraalVM fetcher indefinitely.

**Running `native-image` — ours regardless.** This half does not change if the module becomes
available. As of v0.12.0 its rule still loads `apple_support` and calls `apple_support.run`
(`internal/native_image/rules.bzl`), which puts `SDKROOT` into the action environment; Bazel's
`XcodeLocalEnvProvider` then injects it again and the build dies with *Multiple entries with
same key: SDKROOT*. Until that changes, a rule that works on macOS has to invoke the launcher
directly, and that is what `clj_native_binary` does.

## Publishing

A Clojure library that builds with Bazel still, almost always, carries a `build.clj`, a
`:build` alias and a `tools.build` dependency — a second build system kept alive to write a
pom and make some HTTP requests. `clj_maven_export` is the claim that a ruleset which already
knows the sources, the metadata and the packaging should not need one.

The whole design is one boundary, drawn between two jobs that publishing tools habitually run
together:

| `bazel build //:lib` | pom + jar. A pure function of the source tree — cached, sandboxed, and worth running on every pull request, which is what catches a malformed pom months before a release. |
| `bazel run //:lib.publish` | the upload. Not hermetic, not cacheable, not idempotent, since Clojars releases are immutable and a second attempt is an error. A `manual` leaf, the same shape as `oci_push`. |

Three consequences follow, and each is visible in the API:

- **The version lives in a file**, read by an action rather than during analysis. So the
  outputs are `pom.xml` and `{name}.jar` rather than `{artifact}-{version}.jar`, and the
  coordinates travel to the publisher in a `coordinates.txt`. Bumping a version re-runs the
  two actions that read it — the pom generator, and the packaging step, since the pom is
  embedded in the jar — and re-analyses nothing at all. Nothing in the build graph has to
  know the version, which is the property being bought; the action count is what it costs.
- **Credentials are read by the publisher process**, from `CLOJARS_USERNAME` /
  `CLOJARS_PASSWORD` (or `MAVEN_USER` / `MAVEN_PASSWORD`), never by Bazel. A secret that
  reaches an action's environment reaches its cache key and its execution log with it.
- **The pom's dependencies come from the `deps.edn`, not from the Bazel graph.** The top-level
  `:deps` only, emitted verbatim — no resolution, no version selection, aliases invisible. A
  library's published contract is the set of coordinates its consumers must resolve, which is
  what the `deps.edn` states; the Bazel graph holds one lockfile's *answer* to that question,
  and publishing an answer as though it were the question is how a hand-pinned transitive
  graph gets silently unpinned. `//tests/maven` checks the generated pom against one that was
  really deployed to Clojars, whose fourteen hand-pinned Netty artifacts are exactly that case.

The publisher is JDK-only, like the compiler shim, for the same reason: a ruleset that needs a
dependency resolver to build itself makes every consumer pay for it, and nine HTTP PUTs for a
release — a jar and a pom with their md5 and sha1, then the version list with its own — do not
need Aether.

The checksum set is md5 and sha1 because that is what a repository accepts, not because
stronger ones would cost anything to compute. It defaulted to sha256 and sha512 as well,
on the reasoning that the digests are free and stronger ones are what anyone verifying an
artifact would use — which was right about the digests and wrong about the repositories.
Clojars answers a `.sha256` upload with 400, and does so *after* accepting the jar, so the
release fails half way through rather than at the start. `--checksums` opts into the
stronger pair for a repository that takes them, which Maven Central does. Nothing short of
a real upload could have found this: a dry run does not ask the repository anything.

The **version list is the deploy's completion signal**, and sending it is not optional. This
publisher first declined to write `maven-metadata.xml` at all, reasoning that a release version
does not need one because the repository derives its own. Clojars does not: it treats the
metadata upload as the signal that a deploy has finished, so without it every artifact `PUT` is
answered `201` and the version still never appears — no error, no artifact. Two releases were
lost that way before the cause was clear. It is uploaded last, and **merged** into whatever the
repository already holds rather than written fresh, because that one file is the artifact's
entire history: a publish that replaced it would finish the deploy and un-list every earlier
release in the same breath. A metadata document that cannot be parsed stops the publish instead
of being overwritten.

## Non-goals

- **Windows.** Untested and unclaimed until someone runs it there.
- **Signing, and Maven Central.** Clojars does not require signatures, and Central requires
  considerably more than one; a half-implemented signing story is worse than an absent one.
  `clj_maven_export` publishes source jars to a Maven repository, which is what a Clojure
  library is.
- **Leiningen or boot project files.** `deps.edn` is the format with a specification and a
  library to read it.
- **Being a drop-in replacement** for another ruleset. Migration is a port, and the API is
  designed for what it should be rather than for what an existing one already is.
- **Cross-compiling native images.** GraalVM cannot, so neither can this. One runner per target
  platform, and the release process owns stitching them together.
- **Hermetic native images.** See above: the linker is the platform's. Everything *except* the
  final link is under Bazel's control, and that is the most that is honestly available.
