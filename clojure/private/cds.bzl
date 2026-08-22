"""A class data sharing archive for the compiler's JVM.

Loading clojure.core is most of what a compile action's JVM does before it does any
work. A CDS archive holds those classes pre-parsed, so each action maps them instead of
loading them — startup made cheap rather than rare, which is why this ruleset has no
persistent worker (docs/design.md).

The archive is produced by performing a real compile under
`-XX:ArchiveClassesAtExit`, not by loading namespaces speculatively: what belongs in it
is exactly what compilation touches.

One constraint governs everything here. A CDS archive records the classpath it was
dumped with, and the JVM only uses it when the runtime classpath *starts with* that
same list. So the fixed part — the Clojure runtime, then the shim — must come first in
both, which is why `cds_prefix` exists and why both this rule and the compile action
call it rather than each building a classpath of their own.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")

def cds_prefix(clojure_runtime, shim):
    """The classpath prefix shared by the archive and every compile action.

    Args:
      clojure_runtime: JavaInfo for the toolchain's Clojure runtime.
      shim: the compiler shim File.

    Returns:
      A list of path strings, in the order both sides must use.
    """
    return [f.path for f in clojure_runtime.transitive_runtime_jars.to_list()] + [shim.path]

def _clj_cds_archive_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    shim = ctx.file._aot
    runtime = ctx.toolchains["//clojure:toolchain_type"].clojure.runtime
    archive = ctx.actions.declare_file(ctx.label.name + ".jsa")

    args = ctx.actions.args()
    args.add("-XX:ArchiveClassesAtExit=" + archive.path)

    # The training run is allowed to be noisy: dumping always reports classes it
    # declined to archive, and those warnings are not actionable here.
    args.add("-Xlog:cds=off")
    args.add("-Xlog:cds+dynamic=off")

    # Exactly the prefix, and nothing more: an archive is only used when the runtime
    # classpath BEGINS with the dump-time classpath, so an extra entry here would
    # invalidate every lookup later — silently, since -Xshare:auto just carries on.
    args.add("-cp")
    args.add(":".join(cds_prefix(runtime, shim)))
    args.add("dev.palermo.rulesclj.Aot")
    args.add("--warmup=true")

    ctx.actions.run(
        executable = jdk.java_executable_exec_path,
        arguments = [args],
        inputs = depset(
            [shim],
            transitive = [runtime.transitive_runtime_jars, jdk.files],
        ),
        outputs = [archive],
        mnemonic = "ClojureCdsArchive",
        progress_message = "Building the Clojure compiler's CDS archive",

        # Never cached, local only, and that is not a missed optimisation.
        #
        # A CDS archive records each classpath jar's path, size AND timestamp, and
        # refuses to load if any of them moved. Bazel guarantees the first two and not
        # the third: a jar's mtime differs between machines and between workspaces. An
        # archive fetched from a cache was therefore dumped against timestamps that no
        # longer hold, and every compile action silently loses the archive it is being
        # handed — "timestamp has changed", visible only under -Xshare:on.
        #
        # Dumping it in the same build that uses it costs about half a second per clean
        # build and makes the thing actually work.
        execution_requirements = {
            "no-cache": "1",
            "no-remote-cache": "1",
            "no-remote-exec": "1",
            "local": "1",
        },
    )

    return [DefaultInfo(files = depset([archive]))]

clj_cds_archive = rule(
    implementation = _clj_cds_archive_impl,
    doc = "Dumps a CDS archive covering everything a Clojure compile touches on startup.",
    attrs = {
        "_aot": attr.label(
            default = Label("//src/main/java/dev/palermo/rulesclj:aot_deploy.jar"),
            allow_single_file = True,
            cfg = "exec",
        ),
        "_jdk": attr.label(
            default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
            cfg = "exec",
        ),
    },
    toolchains = ["//clojure:toolchain_type"],
)
