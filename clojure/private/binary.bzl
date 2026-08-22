"""clj_binary and clj_repl — running Clojure, rather than packaging it.

Both are macros over java_binary rather than rules of their own. That is not
laziness: java_binary already solves launcher scripts, runfiles, JVM flags,
`_deploy.jar`, and every platform quirk in starting a JVM. Reimplementing it to
own the rule would be a worse binary with the same behaviour, and the deploy jar
in particular is what phase 5b's native image consumes.
"""

load("@rules_java//java:defs.bzl", "java_binary")

CLOJURE_RUNTIME = Label("//clojure/runtime:clojure")

def clj_binary(
        name,
        main = None,
        main_class = None,
        deps = [],
        runtime_deps = [],
        jvm_flags = [],
        **kwargs):
    """A runnable Clojure program.

    Args:
      name: target name.
      main: namespace whose -main to run. Loaded at startup by clojure.main.
      main_class: a compiled class to run instead, for an AOT'd :gen-class entry
        point. Mutually exclusive with `main`, and required for native images,
        which have no runtime compiler to load a namespace with.
      deps: libraries on the classpath.
      runtime_deps: libraries on the classpath but not depended on at load time.
      jvm_flags: flags for the JVM.
      **kwargs: passed through to java_binary.
    """
    if main and main_class:
        fail("clj_binary: give either main (a namespace) or main_class (a compiled class), not both")
    if not main and not main_class:
        fail("clj_binary: one of main (a namespace) or main_class (a compiled class) is required")

    args = kwargs.pop("args", [])
    java_binary(
        name = name,
        main_class = main_class or "clojure.main",
        args = (["-m", main] if main else []) + args,
        runtime_deps = deps + runtime_deps + [CLOJURE_RUNTIME],
        jvm_flags = [
            # Without this a failure prints a bare stack trace to stdout and the
            # exit code is the only signal; with it, the report goes to stderr
            # where a build tool is looking for it.
            "-Dclojure.main.report=stderr",
        ] + jvm_flags,
        **kwargs
    )

def clj_repl(name, deps = [], runtime_deps = [], jvm_flags = [], **kwargs):
    """An interactive REPL over the given dependencies.

    Args:
      name: target name.
      deps: libraries on the REPL's classpath.
      runtime_deps: further libraries on the classpath.
      jvm_flags: flags for the JVM.
      **kwargs: passed through to java_binary.
    """
    java_binary(
        name = name,
        main_class = "clojure.main",
        args = ["--repl"],
        runtime_deps = deps + runtime_deps + [CLOJURE_RUNTIME],
        jvm_flags = ["-Dclojure.main.report=stderr"] + jvm_flags,
        **kwargs
    )
