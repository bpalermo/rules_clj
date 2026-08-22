"""The ahead-of-time compile action.

One action per target, one JVM per action, no worker. Startup is made cheap rather
than rare — see docs/design.md — and that tradeoff gets measured rather than assumed.
"""

load("@bazel_skylib//rules:common_settings.bzl", "BuildSettingInfo")
load("@rules_java//java/common:java_common.bzl", "java_common")
load(":cds.bzl", "cds_prefix")
load(":jar.bzl", "jar_entry_path")

def source_roots(files, strip_prefix):
    """Returns the classpath roots that make these files loadable by namespace.

    Clojure resolves `foo.bar-baz` by looking for `foo/bar_baz.clj` on the classpath,
    so the compiler needs the directory where the namespace tree starts — not the
    files themselves. That directory is whatever remains of a file's path once its
    in-jar entry path is removed: the same calculation the packaging does, run
    backwards.

    Args:
      files: the Files whose roots are needed.
      strip_prefix: the rule's strip_prefix.

    Returns:
      A sorted, deduplicated list of directory paths.
    """
    roots = {}
    for f in files:
        entry = jar_entry_path(f, strip_prefix)
        path = f.path
        if not path.endswith(entry):
            fail("cannot derive a source root for {}: expected it to end with {}".format(path, entry))
        root = path[:len(path) - len(entry)].rstrip("/")

        # An empty root means the file sits at the execroot itself; "." keeps that a
        # usable classpath entry rather than an empty string the JVM ignores.
        roots[root if root else "."] = True
    return sorted(roots)

def compile_namespaces(
        ctx,
        output,
        namespaces,
        srcs,
        resources,
        strip_prefix,
        deps,
        clojure_runtime):
    """Compiles namespaces ahead of time and packages the classes into a jar.

    Args:
      ctx: the rule context.
      output: the jar File to produce.
      namespaces: namespace names to compile.
      srcs: Clojure sources — on the compile classpath, not copied into the jar.
      resources: files copied into the jar verbatim, and on the compile classpath.
      strip_prefix: prefix removed when computing classpath roots and jar entries.
      deps: JavaInfos the namespaces need in order to load.
      clojure_runtime: JavaInfo for the Clojure runtime the toolchain selected.
    """
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    shim = ctx.file._aot

    dep_jars = depset(
        transitive = [d.transitive_runtime_jars for d in deps] +
                     [clojure_runtime.transitive_runtime_jars],
    )

    # Order is dictated by the CDS archive: the JVM only uses one when the runtime
    # classpath begins with the classpath the archive was dumped with, so the Clojure
    # runtime and the shim come first and everything else follows.
    #
    # A useful consequence, rather than a concession. With dependency jars ahead of the
    # source roots, a namespace that is already compiled in a dependency is found there
    # first and emits nothing — which the guard in the shim reports as two targets
    # declaring one namespace. Source-first would instead compile it again and ship the
    # same classes in two jars, which is the failure this ruleset exists to avoid.
    prefix = cds_prefix(clojure_runtime, shim)
    dep_paths = [f.path for f in dep_jars.to_list() if f.path not in prefix]
    classpath = prefix + dep_paths + source_roots(srcs + resources, strip_prefix)

    # Entries are computed here rather than in a map_each closure so that a bad
    # strip_prefix fails during analysis, naming the label, instead of during
    # execution with a Starlark stack trace.
    resource_flags = [
        "{}={}".format(jar_entry_path(f, strip_prefix), f.path)
        for f in resources
    ]

    cds_mode = ctx.attr._cds_mode[BuildSettingInfo].value
    use_cds = cds_mode != "off"

    args = ctx.actions.args()
    args.add("--classpath=" + ":".join(classpath))
    args.add("--output=" + output.path)

    # Scratch, deliberately adjacent to the output and deliberately undeclared: the
    # sandbox discards it, so a class left by a previous run cannot reach a jar.
    args.add("--classes-dir=" + output.path + ".classes")
    args.add_all(namespaces, format_each = "--namespace=%s")
    args.add_all(resource_flags, format_each = "--resource=%s")

    # Always a param file, and one that holds exactly the request. Bazel hands its
    # contents to the worker as the work request, and passes anything left on the command
    # line to the worker at startup instead — so the JVM flags live in a separate Args.
    # It also keeps a real project's classpath from outgrowing ARG_MAX in the plain path,
    # where the JDK reads the same file as an argfile.
    args.use_param_file("@%s", use_always = True)
    args.set_param_file_format("multiline")

    if ctx.attr._worker_mode[BuildSettingInfo].value:
        # A worker saves JVM startup and a warm JIT, and nothing else: each request still
        # loads its own Clojure in its own classloader, because a runtime shared between
        # targets carries one target's namespaces into the next. See Aot.Clojure.
        ctx.actions.run(
            executable = ctx.executable._worker,
            arguments = [args],
            inputs = depset(srcs + resources, transitive = [dep_jars]),
            outputs = [output],
            mnemonic = "ClojureCompile",
            progress_message = "Compiling %{label}",
            execution_requirements = {
                "supports-workers": "1",
                "requires-worker-protocol": "json",
            },
        )
        return

    java_args = ctx.actions.args()
    if use_cds:
        java_args.add("-XX:SharedArchiveFile=" + ctx.file._cds.path)

        # `on` makes a mismatched archive fail the action instead of being ignored, which
        # is the only way to tell a working archive from one that is silently doing
        # nothing. See //clojure:cds for why this is off by default.
        java_args.add("-Xshare:" + cds_mode)
    java_args.add("-cp", shim.path)
    java_args.add("dev.palermo.rulesclj.Aot")

    ctx.actions.run(
        executable = jdk.java_executable_exec_path,
        arguments = [java_args, args],
        inputs = depset(
            srcs + resources + [shim] + ([ctx.file._cds] if use_cds else []),
            transitive = [dep_jars, jdk.files],
        ),
        outputs = [output],
        mnemonic = "ClojureCompile",
        progress_message = "Compiling %{label}",
    )

COMPILE_ATTRS = {
    "_aot": attr.label(
        doc = "The compiler shim. No third-party dependencies, by design.",
        default = Label("//src/main/java/dev/palermo/rulesclj:aot_deploy.jar"),
        allow_single_file = True,
        cfg = "exec",
    ),
    "_jdk": attr.label(
        default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
        providers = [java_common.JavaRuntimeInfo],
        cfg = "exec",
    ),
    "_cds": attr.label(
        doc = "Pre-parsed Clojure classes, so each compile JVM maps them instead of loading them.",
        default = Label("//clojure/runtime:cds"),
        allow_single_file = True,
        cfg = "exec",
    ),
    "_cds_mode": attr.label(default = Label("//clojure:cds")),
    "_worker": attr.label(
        doc = "The compiler as a Bazel persistent worker.",
        default = Label("//src/main/java/dev/palermo/rulesclj:worker"),
        executable = True,
        cfg = "exec",
    ),
    "_worker_mode": attr.label(default = Label("//clojure:worker")),
}
