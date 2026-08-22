# AGENTS.md

Guidance for coding agents working in this repository.

`rules_clj` is a Bazel ruleset for Clojure, written from scratch. Read
[`docs/design.md`](docs/design.md) before changing anything: it is the specification, it is
written before the code, and disagreements with it should be resolved there first.

## The one rule that is not negotiable

This project is an **independent implementation**, and `NOTICE` says so. Therefore:

- **Do not read, copy from, or consult `griffinbank/rules_clojure` or `simuons/rules_clojure`
  while working here** — not for reference, not to check an edge case, not to compare an error
  message. If a behaviour is unclear, derive it from Clojure's documented compilation semantics,
  from Bazel's rule documentation, or from an experiment in this repository.
- Never copy comments, prose, documentation, test fixtures or error strings from either.
- Code from `bpalermo/protoc-gen-clojure` **is** reusable here — same author, same licence.

Ideas are free; expression is not. The architecture here differs from prior art on purpose, and
that difference is worth preserving even where a similar-looking shortcut exists.

## Layout

| | |
|---|---|
| `clojure/defs.bzl` | the public API — the only load path consumers use |
| `clojure/private/` | rule implementations; may change without notice |
| `clojure/toolchain.bzl` | the toolchain type, `clj_toolchain`, and the runtime alias the java_* macros depend on |
| `clojure/extensions.bzl` | the module extension that fetches Clojure, pinned by digest |
| `clojure/runtime/` | what lands on a program's classpath: the toolchain's Clojure, and the test runner |
| `src/main/java/.../` | the compiler shim and the persistent worker — **no third-party dependencies, ever** |
| `tests/conformance/` | what AOT has to get right; each fixture is its own target so the boundaries are real |
| `tests/shim/` | drives the shim's guard into failure states a well-formed BUILD file cannot produce |
| `bazel/dev/` | formatting, wired as both a runnable target and a test |
| `docs/design.md` | the specification |
| `examples/hello/` | a consumer module — its own Bazel module, run from its own directory |

## Commands

```sh
bazel test //...                   # everything, including //bazel/dev:format_test
bazel run //bazel/dev:format       # rewrite Starlark in place
(cd examples/hello && bazel test //...)
```

Formatting is a test, so an unformatted file fails `bazel test //...` rather than a separate CI
job. Run the format target before committing.

## Conventions

- Java in the compiler shim carries **no third-party dependencies**, ever. That constraint is
  load-bearing, not stylistic — see `docs/design.md`. It is why the worker protocol's JSON is
  hand-written in `Json.java`.
- The worker shares a JVM between targets and **never a Clojure runtime**. If you find yourself
  caching a classloader across requests to save the remaining startup, read the worker section of
  `docs/design.md` first: that is where order-dependent compilation comes from.
- Performance claims belong in `tools/benchmark/benchmark.sh`, measured as a slope between two
  project sizes with `--disk_cache=` off. Whole-build wall clock has produced impossible numbers
  here more than once.
- Every rule gets an example module under `examples/`, and every example is a CI target.
- `examples/` is in `.bazelignore`: each one is its own Bazel module and is tested from its own
  directory.
