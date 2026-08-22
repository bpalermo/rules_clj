"""clj_library — a jar of Clojure sources, and a node in the Java dependency graph.

Phase 1 packages sources without compiling them, which is a complete and useful
thing on its own: Clojure loads from source perfectly well, and a project that
never AOTs still needs its namespaces on a classpath Bazel controls. Compilation
arrives in phase 2 behind the `aot` attribute, and it changes what is in the jar
without changing what the rule is.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":compile.bzl", "COMPILE_ATTRS", "compile_namespaces")
load(":jar.bzl", "ZIPPER_ATTR", "build_jar")
load(":providers.bzl", "ClojureInfo")

def _clj_library_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".jar")
    deps = [d[JavaInfo] for d in ctx.attr.deps]
    runtime_deps = [d[JavaInfo] for d in ctx.attr.runtime_deps]

    aot = ctx.attr.aot and ctx.attr.namespaces
    if aot:
        compile_namespaces(
            ctx = ctx,
            output = output,
            namespaces = ctx.attr.namespaces,
            srcs = ctx.files.srcs,
            resources = ctx.files.resources,
            strip_prefix = ctx.attr.strip_prefix,
            deps = deps,
            clojure_runtime = ctx.toolchains["//clojure:toolchain_type"].clojure.runtime,
        )
    else:
        build_jar(ctx, output, ctx.files.srcs + ctx.files.resources, ctx.attr.strip_prefix)

    java_info = JavaInfo(
        output_jar = output,
        # No ijar: there is no Java API surface to abstract over. The jar holds
        # Clojure sources, so the compile-time and runtime views are the same file.
        compile_jar = output,
        deps = deps,
        runtime_deps = runtime_deps,
    )

    return [
        DefaultInfo(
            files = depset([output]),
            runfiles = ctx.runfiles(files = ctx.files.data).merge_all(
                [d[DefaultInfo].default_runfiles for d in ctx.attr.deps + ctx.attr.runtime_deps],
            ),
        ),
        java_common.merge([java_info]),
        ClojureInfo(
            namespaces = depset(
                ctx.attr.namespaces,
                transitive = [
                    d[ClojureInfo].namespaces
                    for d in ctx.attr.deps
                    if ClojureInfo in d
                ],
            ),
            srcs = depset(ctx.files.srcs),
            aot = aot,
        ),
    ]

clj_library = rule(
    implementation = _clj_library_impl,
    doc = "Packages Clojure sources into a jar that Java rules can depend on.",
    attrs = {
        "srcs": attr.label_list(
            doc = "Clojure sources (.clj, .cljc, .cljs).",
            allow_files = [".clj", ".cljc", ".cljs"],
        ),
        "resources": attr.label_list(
            doc = "Files to include in the jar as-is.",
            allow_files = True,
        ),
        "strip_prefix": attr.string(
            doc = """Path prefix to remove from every src and resource.

Clojure finds `foo.bar-baz` by looking for `foo/bar_baz.clj` on the classpath, so
a source at `src/foo/bar_baz.clj` needs `strip_prefix = "src"` to be loadable. A
file outside the prefix is an error rather than a silently unloadable jar.""",
        ),
        "deps": attr.label_list(
            doc = "Libraries needed to load these namespaces.",
            providers = [JavaInfo],
        ),
        "runtime_deps": attr.label_list(
            doc = "Libraries needed at runtime but not to load these namespaces.",
            providers = [JavaInfo],
        ),
        "data": attr.label_list(
            doc = "Files needed at runtime.",
            allow_files = True,
        ),
        "namespaces": attr.string_list(
            doc = """Namespaces this target provides, and — unless `aot` is off — compiles.

Declared rather than inferred: inferring it means parsing source during analysis,
which Bazel does not allow.""",
        ),
        "aot": attr.bool(
            doc = """Compile the declared namespaces ahead of time. On by default.

Compiling is the point of the rule, so it is the default, and it is stricter than
packaging: everything a namespace loads must be reachable through `deps`, because
compilation has to load it. A target with no `namespaces` has nothing to compile and
packages its sources whatever this says.

Turning it off produces a jar of sources that Clojure loads at runtime. That is a
legitimate choice for a namespace whose compilation has side effects you would rather
not have at build time — but note that a GraalVM native image cannot use it, since the
image has no compiler to load source with.""",
            default = True,
        ),
    } | ZIPPER_ATTR | COMPILE_ATTRS,
    provides = [JavaInfo, ClojureInfo],
    toolchains = ["//clojure:toolchain_type"],
)
