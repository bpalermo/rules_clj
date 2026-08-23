"""Lint aspects, exposed as ordinary Bazel tests.

Wiring lint as `lint_test` targets rather than as an `aspect lint` invocation
means `bazel test //...` covers it and CI needs no separate lint job — the same
arrangement //bazel/dev uses for formatting.

One linter, for the one language present outside Starlark and Clojure: the shell
scripts that stamp builds, cut releases and run benchmarks. None of them is a
Bazel binary — they are invoked by path — so sh_library targets exist at each
script's package purely to give the aspect something to attach to.

Starlark lint stays with buildifier_prebuilt in //bazel/dev; a buildifier lint
aspect is not possible here (its binaries live in repos internal to the
buildifier_prebuilt module, out of a lint_test's runfiles reach).
"""

load("@aspect_rules_lint//lint:lint_test.bzl", "lint_test")
load("@aspect_rules_lint//lint:shellcheck.bzl", "lint_shellcheck_aspect")

shellcheck = lint_shellcheck_aspect(
    binary = Label("@aspect_rules_lint//lint:shellcheck_bin"),
    config = Label("//:.shellcheckrc"),
)

shellcheck_test = lint_test(aspect = shellcheck)
