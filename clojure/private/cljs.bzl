"""cljs_library — compiling ClojureScript.

A different compiler with a different output, but the same shape of problem: a JVM, a
classpath assembled from Bazel's graph, and a declared output. What differs is that
ClojureScript's compiler is not incremental per namespace the way the JVM one is — it
compiles a build, following requires from an entry point — so the unit here is a build,
not a namespace.

The ClojureScript compiler and the Google Closure compiler it drives come from the
project's own lockfile, like any other dependency. This ruleset pins no ClojureScript
version of its own: which one a project uses is the project's business.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":compile.bzl", "source_roots")
load(":providers.bzl", "ClojureInfo")

def _cljs_library_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    output = ctx.actions.declare_directory(ctx.label.name + ".out")

    # Clojure comes from the toolchain, ClojureScript from the project's dependencies.
    # The split is deliberate: the compiler you run is your project's choice, while the
    # Clojure it runs on is a property of the build, decided in one place.
    #
    # It is also load-bearing rather than tidy. tools.deps records why each library was
    # included, so a library named directly in deps.edn — clojure usually is — has no
    # dependents and therefore appears in the lock as nobody's dependency, even where a
    # POM says otherwise. Relying on the graph alone gets you ClassNotFoundException:
    # clojure.main the first time you compile ClojureScript.
    runtime = ctx.toolchains["//clojure:toolchain_type"].clojure.runtime
    deps = [d[JavaInfo] for d in ctx.attr.deps]
    dep_jars = depset(
        transitive = [d.transitive_runtime_jars for d in deps] + [runtime.transitive_runtime_jars],
    )

    roots = source_roots(ctx.files.srcs, ctx.attr.strip_prefix)
    classpath = [f.path for f in dep_jars.to_list()] + roots

    # Compiler options as EDN on the command line. cljs.main also accepts a file, which is
    # what a project with real build configuration should use — `opts_file` — but the
    # common case is two or three keys and a file for those is ceremony.
    #
    # A value beginning with ':' is written as a keyword rather than a string, because
    # most of these options take keywords and Starlark has no keyword type. Getting this
    # wrong is not loud: :optimizations "simple" is accepted, ignored, and the build then
    # fails somewhere else entirely with "Could not write JavaScript nil".
    options = dict(ctx.attr.compiler_options)
    options["output-dir"] = output.path
    options["output-to"] = output.path + "/" + ctx.attr.output_file
    pairs = []
    for key, value in sorted(options.items()):
        rendered = value if value.startswith(":") else '"{}"'.format(value)
        pairs.append(":{} {}".format(key, rendered))
    edn = "{" + " ".join(pairs) + "}"

    args = ctx.actions.args()
    args.add("-cp")
    args.add(":".join(classpath))
    args.add("clojure.main")
    args.add("-m", "cljs.main")
    if ctx.file.opts_file:
        args.add("-co", ctx.file.opts_file)
    args.add("-co", edn)
    args.add("-c", ctx.attr.main)

    ctx.actions.run(
        executable = jdk.java_executable_exec_path,
        arguments = [args],
        inputs = depset(
            ctx.files.srcs + ([ctx.file.opts_file] if ctx.file.opts_file else []),
            transitive = [dep_jars, jdk.files],
        ),
        outputs = [output],
        mnemonic = "ClojureScriptCompile",
        progress_message = "Compiling ClojureScript %{label}",
    )

    return [
        DefaultInfo(files = depset([output])),
        ClojureInfo(
            namespaces = depset([ctx.attr.main]),
            srcs = depset(ctx.files.srcs),
            aot = True,
        ),
    ]

cljs_library = rule(
    implementation = _cljs_library_impl,
    doc = "Compiles ClojureScript to JavaScript with cljs.main.",
    attrs = {
        "srcs": attr.label_list(
            doc = "ClojureScript sources.",
            allow_files = [".cljs", ".cljc", ".clj", ".js"],
        ),
        "main": attr.string(
            doc = "The entry namespace; requires are followed from here.",
            mandatory = True,
        ),
        "output_file": attr.string(
            doc = "Name of the JavaScript file to produce inside the output directory.",
            default = "main.js",
        ),
        "strip_prefix": attr.string(
            doc = "Path prefix removed when computing classpath roots, as for clj_library.",
        ),
        "compiler_options": attr.string_dict(
            doc = """Compiler options, merged into the ones this rule sets.

Keys are written without the leading colon. A value that begins with a colon is passed
through as a keyword — `{"optimizations": ":simple"}` — which is what most of these
options expect.""",
            default = {},
        ),
        "opts_file": attr.label(
            doc = "A build.edn of compiler options, for configuration too large to inline.",
            allow_single_file = [".edn"],
        ),
        "deps": attr.label_list(
            doc = "Jars on the compile classpath — ClojureScript itself, and any libraries.",
            providers = [JavaInfo],
        ),
        "_jdk": attr.label(
            default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
            cfg = "exec",
        ),
    },
    toolchains = ["//clojure:toolchain_type"],
)
