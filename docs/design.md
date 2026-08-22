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

## Startup, and why there is no worker

Per-action JVM startup is the reason Clojure rulesets reach for persistent workers: loading
`clojure.core` costs on the order of a second, and paying it per target dominates a build.

A worker is not the only way to avoid it, and it is an expensive one — a long-lived JVM
compiling arbitrary user code needs a classloader cache, and that cache has to reason about
which classpaths are interchangeable, when a loader must be discarded because it compiled a
protocol, and what state the runtime leaked between jobs.

The alternative is to make startup cheap instead of rare. `clojure.core` ships AOT-compiled, so
its classes can go into a **class data sharing archive** built once by the toolchain and mapped
into every compiler JVM with `-XX:SharedArchiveFile`. Startup drops to a fraction, the process
stays disposable, and every hazard above disappears with it — no cache, no cross-job state, no
JarHell.

This is a bet, and it is falsifiable: phase 2 benchmarks it against an existing worker-based
ruleset on a fixture of libraries that are slow to AOT. If the numbers do not hold, a worker is
built then, with measurements in hand rather than inherited assumptions. The order matters —
correct and simple first, fast second, and never fast in a way nobody can explain.

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

## Public API

One load path — `@rules_clj//clojure:defs.bzl` — and everything else is private.

```starlark
clj_library(name, srcs, deps, runtime_deps, aot, resources, ...)
clj_binary(name, main, deps, ...)
clj_test(name, ns, deps, ...)
clj_repl(name, deps, dirs, ...)
```

Two deliberate departures from what a Clojure ruleset usually looks like:

- **A real toolchain.** `//clojure:toolchain_type` carries the Clojure runtime and the compiler
  shim, so the Clojure version is a configuration choice — selectable per platform, overridable
  per build — rather than something baked into the ruleset's own dependencies.
- **A `ClojureInfo` provider** alongside `JavaInfo`, recording which namespaces a target
  provides, its sources, and whether it was AOT'd. Downstream rules and the BUILD generator then
  read the graph instead of re-parsing source to rediscover it.

## Non-goals

- **Windows.** Untested and unclaimed until someone runs it there.
- **Leiningen or boot project files.** `deps.edn` is the format with a specification and a
  library to read it.
- **Being a drop-in replacement** for another ruleset. Migration is a port, and the API is
  designed for what it should be rather than for what an existing one already is.
