"""The native-image toolchain, and the rule that uses it.

Separate from the Clojure toolchain because they answer different questions and most
projects only need the first. A build that never produces a native binary should not
download a GraalVM to find that out.
"""

ClojureNativeToolchainInfo = provider(
    doc = "A GraalVM capable of building native images.",
    fields = {
        "launcher": "File — the native-image executable.",
        "sdk": "depset — the whole SDK, which the launcher needs to resolve its own JAVA_HOME.",
        "version": "string — the GraalVM version.",
    },
)

def _clj_native_toolchain_impl(ctx):
    return [platform_common.ToolchainInfo(
        native = ClojureNativeToolchainInfo(
            launcher = ctx.file.launcher,
            # The whole tree, not just the launcher: bin/native-image is a symlink into
            # lib/svm/bin and resolves its JAVA_HOME relative to its own location, so an
            # action given only the launcher fails in a way that reads like a corrupt
            # download.
            sdk = ctx.attr.sdk[DefaultInfo].files,
            version = ctx.attr.version,
        ),
    )]

clj_native_toolchain = rule(
    implementation = _clj_native_toolchain_impl,
    doc = "Declares a GraalVM as the toolchain for building native images.",
    attrs = {
        "launcher": attr.label(allow_single_file = True, mandatory = True),
        "sdk": attr.label(mandatory = True),
        "version": attr.string(mandatory = True),
    },
)

def _clj_native_binary_impl(ctx):
    toolchain = ctx.toolchains["//clojure:native_toolchain_type"].native
    output = ctx.actions.declare_file(ctx.attr.binary_name or ctx.label.name)

    args = ctx.actions.args()
    args.add_all(ctx.attr.extra_args)

    # --no-fallback is not optional and is not exposed. Without it, an image the analysis
    # could not close falls back to bundling a JVM: it works, it is enormous, and it
    # starts as slowly as the thing a native image was built to avoid. A build error is
    # the honest outcome.
    args.add("--no-fallback")

    # Clojure's namespaces are constructed by static initialisers, and in a native image
    # there is no compiler to run them later.
    args.add("--initialize-at-build-time")
    args.add_all(["-jar", ctx.file.jar])
    args.add(output.path)

    ctx.actions.run(
        executable = toolchain.launcher.path,
        arguments = [args],
        inputs = depset([ctx.file.jar], transitive = [toolchain.sdk]),
        outputs = [output],
        mnemonic = "ClojureNativeImage",
        progress_message = "Building native image %{output}",

        # Not hermetic, stated rather than hidden. native-image shells out to the
        # platform's linker — clang on macOS, gcc on Linux — and a scrubbed environment
        # leaves it unable to find one. Deliberately NOT routed through apple_support,
        # which would put SDKROOT in the action environment on top of the one Bazel's own
        # XcodeLocalEnvProvider injects; Bazel does not merge those, it aborts with
        # "Multiple entries with same key: SDKROOT".
        execution_requirements = {
            "local": "1",
            "no-sandbox": "1",
            "no-remote": "1",
        },
        use_default_shell_env = True,
    )

    return [DefaultInfo(executable = output, files = depset([output]))]

clj_native_binary = rule(
    implementation = _clj_native_binary_impl,
    doc = """Compiles a deploy jar into a native executable with GraalVM.

The jar must be fully ahead-of-time compiled and carry a Main-Class: a native image has no
Clojure compiler in it, so a namespace that would be loaded from source at runtime cannot
exist. In practice that means every clj_library it contains was built with `aot = True`
and the entry point is a real `:gen-class`.""",
    executable = True,
    attrs = {
        "jar": attr.label(
            doc = "A deploy jar — clj_binary's `<name>_deploy.jar`.",
            allow_single_file = [".jar"],
            mandatory = True,
        ),
        "binary_name": attr.string(doc = "Name of the executable; defaults to the target name."),
        "extra_args": attr.string_list(
            doc = "Further native-image flags, e.g. --static --libc=musl on linux x86_64.",
            default = [],
        ),
    },
    toolchains = ["//clojure:native_toolchain_type"],
)
