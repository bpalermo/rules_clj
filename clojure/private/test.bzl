"""clj_test — one namespace's clojure.test suite as a Bazel test target.

A macro over java_test with Bazel's own JUnit runner turned off: the thing being
run is a Clojure namespace, not a JUnit class, so our runner is the main class and
the namespace is its argument. See //clojure/runtime for what it reports.

One namespace per target is a deliberate constraint. Bazel's unit of caching,
parallelism and flakiness is the target, so a target per namespace is what makes
those work; a target covering twenty namespaces reruns all twenty when one file
changes.
"""

load("@rules_java//java:defs.bzl", "java_test")

RUNNER = Label("//clojure/runtime:runner")
CLOJURE_RUNTIME = Label("//clojure/runtime:clojure")

def clj_test(name, ns, deps = [], runtime_deps = [], jvm_flags = [], **kwargs):
    """Runs clojure.test for one namespace.

    Args:
      name: target name.
      ns: the namespace to test, e.g. "example.core-test".
      deps: libraries on the classpath — the code under test and its dependencies.
      runtime_deps: further libraries on the classpath.
      jvm_flags: flags for the JVM.
      **kwargs: passed through to java_test.
    """
    java_test(
        name = name,
        main_class = "clojure.main",
        use_testrunner = False,
        args = ["-m", "rules-clj.runner", ns],
        runtime_deps = deps + runtime_deps + [RUNNER, CLOJURE_RUNTIME],
        jvm_flags = ["-Dclojure.main.report=stderr"] + jvm_flags,
        **kwargs
    )
