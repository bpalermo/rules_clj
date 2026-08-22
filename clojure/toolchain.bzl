"""The Clojure toolchain: which Clojure runs, and what compiles with it.

A toolchain rather than a hardcoded dependency because the Clojure version is a
property of the build, not of this ruleset. A project pinned to 1.11 should not
have to fork the rules to say so, and a project testing against two versions
should be able to select between them the way it selects any other toolchain.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

ClojureToolchainInfo = provider(
    doc = "The Clojure runtime a build compiles and runs against.",
    fields = {
        "runtime": "JavaInfo — clojure.jar and whatever it needs on the classpath.",
        "version": "string — the Clojure version, for diagnostics and stamping.",
    },
)

def _clj_toolchain_impl(ctx):
    runtime = java_common.merge([d[JavaInfo] for d in ctx.attr.runtime])
    return [
        platform_common.ToolchainInfo(
            clojure = ClojureToolchainInfo(
                runtime = runtime,
                version = ctx.attr.version,
            ),
        ),
    ]

clj_toolchain = rule(
    implementation = _clj_toolchain_impl,
    doc = "Declares a Clojure runtime as a toolchain.",
    attrs = {
        "runtime": attr.label_list(
            doc = "Jars making up the Clojure runtime — clojure, spec.alpha, core.specs.alpha.",
            providers = [JavaInfo],
            mandatory = True,
        ),
        "version": attr.string(
            doc = "The Clojure version these jars provide.",
            mandatory = True,
        ),
    },
)

def _clj_runtime_alias_impl(ctx):
    toolchain = ctx.toolchains["//clojure:toolchain_type"].clojure
    runtime = toolchain.runtime

    # DefaultInfo as well as JavaInfo, so the same target can be a `data` dependency —
    # a test that needs the jars as files rather than as a classpath entry.
    return [
        runtime,
        DefaultInfo(files = runtime.transitive_runtime_jars),
    ]

clj_runtime_alias = rule(
    implementation = _clj_runtime_alias_impl,
    doc = """Re-exports the resolved toolchain's runtime as an ordinary JavaInfo target.

Rules built on java_binary and java_test are macros, and a macro cannot resolve a
toolchain — that happens during analysis, after macro expansion. Depending on this
alias lets those rules put the selected Clojure on their classpath anyway, so the
toolchain stays the single place the version is decided.""",
    toolchains = ["//clojure:toolchain_type"],
    provides = [JavaInfo],
)
