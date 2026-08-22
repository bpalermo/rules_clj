"""Module extension that fetches a Clojure runtime and registers it as a toolchain.

Deliberately not via rules_jvm_external. Three jars pinned by SHA-256 need a
download and a BUILD file, not a Maven resolver, and a ruleset that drags in a
dependency-resolution framework to place three files makes every consumer pay for
it. Project dependencies are a different problem with a different answer — see
docs/design.md on the deps.edn lockfile.
"""

load("//clojure/private:runtime_repo.bzl", "clojure_runtime_repo")

# Known Clojure runtimes, pinned by digest. Adding a version means adding its three
# jars here; nothing resolves at build time, by design.
_RUNTIMES = {
    "1.12.1": {
        "clojure": [
            "org/clojure/clojure/1.12.1/clojure-1.12.1.jar",
            "87eeea9e355d86c045738af494d683e09e914cb0467ae40d46a66b87a36c72d4",
        ],
        "spec_alpha": [
            "org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar",
            "94cd99b6ea639641f37af4860a643b6ed399ee5a8be5d717cff0b663c8d75077",
        ],
        "core_specs_alpha": [
            "org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar",
            "eb73ac08cf49ba840c88ba67beef11336ca554333d9408808d78946e0feb9ddb",
        ],
    },
}

DEFAULT_CLOJURE_VERSION = "1.12.1"

_toolchain = tag_class(
    doc = "Fetch a Clojure runtime and make it available as a toolchain.",
    attrs = {
        "version": attr.string(
            doc = "Clojure version. Must be one this ruleset knows the digests for.",
            default = DEFAULT_CLOJURE_VERSION,
        ),
        "repositories": attr.string_list(
            doc = "Maven repository base URLs to try, in order.",
            default = ["https://repo1.maven.org/maven2"],
        ),
    },
)

def _clojure_impl(module_ctx):
    versions = {}
    for mod in module_ctx.modules:
        for tag in mod.tags.toolchain:
            if tag.version not in _RUNTIMES:
                fail(
                    "rules_clj does not know Clojure {}. Known versions: {}. ".format(
                        tag.version,
                        ", ".join(sorted(_RUNTIMES)),
                    ) +
                    "Adding one means adding its jar digests to clojure/extensions.bzl.",
                )
            versions[tag.version] = tag.repositories

    # The default exists so that a consumer who wants the ordinary thing writes
    # nothing at all; asking for a version explicitly simply adds to this.
    if not versions:
        versions[DEFAULT_CLOJURE_VERSION] = ["https://repo1.maven.org/maven2"]

    for version, repositories in versions.items():
        clojure_runtime_repo(
            name = "rules_clj_clojure_" + version.replace(".", "_"),
            version = version,
            jars = _RUNTIMES[version],
            repositories = repositories,
        )

    return module_ctx.extension_metadata(
        root_module_direct_deps = "all",
        root_module_direct_dev_deps = [],
        reproducible = True,
    )

clojure = module_extension(
    implementation = _clojure_impl,
    tag_classes = {"toolchain": _toolchain},
)
