# rules_clj

Bazel rules for Clojure.

> **Status: phase 0 — skeleton.** There are no rules here yet. The design is written
> ([`docs/design.md`](docs/design.md)) and the repository builds and checks itself; the API
> arrives in phase 1. Nothing is published to the Bazel Central Registry, and the version is
> deliberately `0.0.0` so that nothing can depend on it by accident.

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
| **No persistent worker** — a class data sharing archive instead | Startup made cheap rather than rare. No classloader cache, so none of the failure modes one brings. |
| `deps.edn` resolved into a **checked-in lockfile** | Builds are hermetic and work offline; no Clojure CLI download, no `~/.m2`. |

The reasoning behind each is in [`docs/design.md`](docs/design.md), including how the no-worker
bet gets tested rather than assumed.

Scope includes **GraalVM native binaries** (`clj_native_binary`). A Clojure CLI or protoc plugin
pays JVM startup on every invocation, so producing a native image is part of shipping Clojure
rather than an afterthought — and it is the rule with the most sharp edges: closed-world
analysis makes AOT a correctness requirement rather than an optimisation, the final link is not
hermetic because it is the platform's, and GraalVM does not cross-compile. The design document
says so plainly instead of finding out later.

## Development

```sh
bazel test //...                   # everything, including formatting
bazel run //bazel/dev:format       # rewrite Starlark in place
```

Requires JDK 21; the build selects a hermetic `remotejdk_21` for both target and tool JVMs.

## Licence

Apache-2.0 — see [`LICENSE`](LICENSE). Copyright 2026 Bruno Palermo.

This is not a fork; see [`NOTICE`](NOTICE) for the statement of independence and for credit to
the prior art that shaped the question.
