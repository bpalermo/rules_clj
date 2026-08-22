"""clj_library — a jar of Clojure sources, and a node in the Java dependency graph.

Phase 1 packages sources without compiling them, which is a complete and useful
thing on its own: Clojure loads from source perfectly well, and a project that
never AOTs still needs its namespaces on a classpath Bazel controls. Compilation
arrives in phase 2 behind the `aot` attribute, and it changes what is in the jar
without changing what the rule is.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":jar.bzl", "ZIPPER_ATTR", "build_jar")
load(":providers.bzl", "ClojureInfo")

def _clj_library_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".jar")
    files = ctx.files.srcs + ctx.files.resources
    build_jar(ctx, output, files, ctx.attr.strip_prefix)

    deps = [d[JavaInfo] for d in ctx.attr.deps]
    runtime_deps = [d[JavaInfo] for d in ctx.attr.runtime_deps]

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
            aot = False,
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
            doc = """Namespaces this target provides.

Informational in phase 1 and recorded in ClojureInfo; phase 2 makes it the set to
compile. Declared rather than inferred, because inferring it means parsing source
during analysis, which Bazel does not allow.""",
        ),
    } | ZIPPER_ATTR,
    provides = [JavaInfo, ClojureInfo],
)
