# native

A Clojure CLI compiled to a native executable with GraalVM.

Not run in CI: building it downloads a GraalVM, which is not a reasonable cost to put on
every pull request. Run it by hand.

```sh
bazel build //src/cli:cli_native
./bazel-bin/src/cli/hello Ada
```

Measured on an M-series laptop, the same program both ways:

| | |
|---|---|
| `bazel-bin/src/cli/hello` (native) | 0.01s, 14MB |
| `bazel-bin/src/cli/cli` (JVM) | 0.28s |

Three things this example is quietly demonstrating:

- **AOT is not optional here.** A native image has no Clojure compiler in it, so a
  namespace that would be loaded from source at runtime cannot exist. `clj_library`
  compiles by default, and the entry point is a real `:gen-class`.
- **`--no-fallback` is on and not configurable.** Without it, an image whose analysis did
  not close falls back to bundling a JVM — it works, it is enormous, and it starts as
  slowly as the thing you were trying to avoid.
- **The final link is not hermetic.** `native-image` shells out to the platform's linker,
  so the action runs unsandboxed with the ambient environment. Everything before the link
  is under Bazel's control; the link is not.
