"""clj_maven_export — a publishable jar, its pom, and a way to push them to Clojars.

Publishing is two different jobs that most tools run together, and separating them is
the whole design here.

Building the artifact is a pure function of the source tree: sources, a deps.edn and a
version file go in, a jar and a pom come out, byte-identical every time. That belongs in
the build graph, where it is cached, sandboxed and — most usefully — CHECKED. `bazel
build //:lib.export` on every pull request tells you the pom is well-formed and the jar
packs, months before anyone tries to release.

Uploading is not a function of anything. It reaches the network, it needs credentials,
and running it twice is not the same as running it once — Clojars refuses the second
attempt, because a released version is immutable. So the upload is a `bazel run` leaf
tagged `manual`, in the same shape as rules_oci's `oci_push` or rules_jvm_external's
`maven.publish`: it consumes build outputs, it produces none, and nothing depends on it.

The boundary is worth stating plainly, because tools in this area routinely blur it:

    bazel build //:lib          hermetic, cacheable, safe to run anywhere
    bazel run   //:lib.publish  not hermetic, not cacheable, not idempotent

Credentials never enter the build. They are read by the publisher process from
CLOJARS_USERNAME / CLOJARS_PASSWORD (or MAVEN_USER / MAVEN_PASSWORD) at the moment of
the request. A secret that reaches an action's environment reaches its cache key and its
execution log too, and there is no version of that which is fine.

One more consequence of the split, and it is the reason the file names look odd: the
VERSION IS NOT KNOWN DURING ANALYSIS. It lives in a file, read by an action. So the
outputs are `pom.xml` and `{name}.jar` rather than `{artifact}-{version}.jar`, and the
coordinates travel to the publisher in `coordinates.txt`. Bazel therefore never
re-analyses the graph because someone bumped a version, and the publisher — which does
know the version — is the one thing that names files after it.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load(":jar.bzl", "ZIPPER_ATTR", "build_jar")

MavenExportInfo = provider(
    doc = "The artifacts of a clj_maven_export, for the publish rule that pushes them.",
    fields = {
        "jar": "File — the source jar, with the pom embedded under META-INF/maven.",
        "pom": "File — the generated pom.xml.",
        "coordinates": "File — one line, group:artifact:version, written by the same action.",
    },
)

def _generate_pom(ctx, pom, properties, coordinates):
    """Runs the pom generator on the toolchain's Clojure.

    Exactly the shape of a compile action (see compile.bzl): the JDK from the current
    java runtime, the toolchain's Clojure jars plus the tool's own jar on the classpath,
    and `clojure.main` as the entry point. Not a `bazel run` of //tools/pom_gen, because
    a launcher script pulls a runfiles tree into every action for nothing; not a new
    toolchain, because the Clojure that compiles the project is the Clojure that should
    write its pom.
    """
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    runtime = ctx.toolchains["//clojure:toolchain_type"].clojure.runtime
    runtime_jars = runtime.transitive_runtime_jars
    tool = ctx.file._pom_gen

    java_args = ctx.actions.args()
    java_args.add_joined(
        "-cp",
        depset([tool], transitive = [runtime_jars]),
        join_with = ":",
    )
    java_args.add("clojure.main")
    java_args.add_all(["-m", "rules-clj.pom-gen"])

    args = ctx.actions.args()
    args.add("--deps-edn=" + ctx.file.deps_edn.path)
    args.add("--version-edn=" + ctx.file.version_file.path)
    args.add("--group-id=" + ctx.attr.group_id)
    args.add("--artifact-id=" + ctx.attr.artifact_id)
    args.add("--pom-out=" + pom.path)
    args.add("--properties-out=" + properties.path)
    args.add("--coordinates-out=" + coordinates.path)

    # Optional metadata is passed only when set: an empty --description= would put an
    # empty element in the pom, which is not the same as leaving it out.
    for flag, value in [
        ("description", ctx.attr.description),
        ("url", ctx.attr.url),
        ("scm-url", ctx.attr.scm_url),
        ("license-name", ctx.attr.license_name),
        ("license-url", ctx.attr.license_url),
        ("source-directory", ctx.attr.strip_prefix),
    ]:
        if value:
            args.add("--{}={}".format(flag, value))

    ctx.actions.run(
        executable = jdk.java_executable_exec_path,
        arguments = [java_args, args],
        inputs = depset(
            [tool, ctx.file.deps_edn, ctx.file.version_file],
            transitive = [runtime_jars, jdk.files],
        ),
        outputs = [pom, properties, coordinates],
        mnemonic = "ClojurePom",
        progress_message = "Generating pom for %{label}",
    )

def _clj_maven_export_impl(ctx):
    # A directory named after the target rather than bare files in the package: the
    # names inside it are fixed — Maven's own — so two exports in one package would
    # otherwise declare the same pom.xml.
    pom = ctx.actions.declare_file(ctx.label.name + "/pom.xml")
    properties = ctx.actions.declare_file(ctx.label.name + "/pom.properties")
    coordinates = ctx.actions.declare_file(ctx.label.name + "/coordinates.txt")
    jar = ctx.actions.declare_file(ctx.label.name + ".jar")

    _generate_pom(ctx, pom, properties, coordinates)

    # The pom goes inside the jar as well as beside it. Maven has put it at
    # META-INF/maven/{group}/{artifact}/ since Maven 2, and enough tooling reads it back
    # out of a jar — dependency scanners, license scanners, `mvn dependency:tree` on a
    # jar with no pom beside it — that omitting it makes the artifact quietly less
    # useful than one built by any other tool.
    meta = "META-INF/maven/{}/{}/".format(ctx.attr.group_id, ctx.attr.artifact_id)
    build_jar(
        ctx,
        jar,
        ctx.files.srcs + ctx.files.resources,
        ctx.attr.strip_prefix,
        extra_entries = {
            meta + "pom.xml": pom,
            meta + "pom.properties": properties,
        },
    )

    return [
        # Both files, so `bazel build` of this target is the artifact-builds check: it
        # runs the generator and the packaging, and fails on a deps.edn a pom cannot
        # express or a strip_prefix that matches nothing.
        DefaultInfo(files = depset([jar, pom])),
        OutputGroupInfo(
            jar = depset([jar]),
            pom = depset([pom]),
            coordinates = depset([coordinates]),
        ),
        MavenExportInfo(jar = jar, pom = pom, coordinates = coordinates),
    ]

_clj_maven_export = rule(
    implementation = _clj_maven_export_impl,
    doc = """Builds a source jar and the pom that describes it.

The jar holds sources, not classes, and that is deliberate rather than unfinished. A
Clojure library compiles in its CONSUMER's process, against the consumer's own versions
of its dependencies; shipping AOT classes freezes a protocol or record against the
versions present when the publisher built it, and the resulting `instance?` failures are
a classic Clojure packaging bug. Every library on Clojars ships sources for this reason.
""",
    attrs = {
        "srcs": attr.label_list(
            doc = "Clojure sources to publish.",
            allow_files = [".clj", ".cljc", ".cljs"],
        ),
        "resources": attr.label_list(
            doc = "Files to include in the jar as-is.",
            allow_files = True,
        ),
        "strip_prefix": attr.string(
            doc = """Path prefix removed from every src and resource.

The same rule as clj_library: `foo.bar-baz` must land at `foo/bar_baz.clj` in the jar.
Also written into the pom as `<sourceDirectory>`, which is what it is.""",
        ),
        "deps_edn": attr.label(
            doc = """The project's deps.edn. Its TOP-LEVEL `:deps` become the pom's
`<dependencies>`, verbatim — see //tools/pom_gen for what that does and does not do.""",
            allow_single_file = True,
            mandatory = True,
        ),
        "version_file": attr.label(
            doc = """A file holding the version — `{:version "1.2.3"}` or the bare string.

A file rather than an attribute so that the version is an action input rather than an
analysis input: bumping it re-runs the two actions that read it — the pom generator, and
the packaging step that embeds the pom in the jar — and re-analyses nothing. The same
file can then be read by a release workflow and a version-consistency test.""",
            allow_single_file = True,
            mandatory = True,
        ),
        "group_id": attr.string(mandatory = True),
        "artifact_id": attr.string(mandatory = True),
        "description": attr.string(),
        "url": attr.string(),
        "scm_url": attr.string(
            doc = "Repository URL, e.g. https://github.com/owner/repo. Becomes `<scm>`.",
        ),
        "license_name": attr.string(doc = "SPDX identifier, e.g. Apache-2.0."),
        "license_url": attr.string(),
        "_pom_gen": attr.label(
            doc = "The pom generator, as a jar to put on an action's classpath.",
            default = Label("//tools/pom_gen:pom_gen_lib"),
            allow_single_file = True,
            cfg = "exec",
        ),
        "_jdk": attr.label(
            default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
            providers = [java_common.JavaRuntimeInfo],
            cfg = "exec",
        ),
    } | ZIPPER_ATTR,
    provides = [MavenExportInfo],
    toolchains = ["//clojure:toolchain_type"],
)

# Resolving the runfiles root, in the three situations this script is started in: by
# `bazel run` (which sets RUNFILES_DIR and puts us in the tree), from a test (which sets
# RUNFILES_DIR), and by path from a release script (which gets a sibling .runfiles
# directory). Written out rather than sourced from the bash runfiles library, because
# the whole launcher is one exec and the library would be the largest thing in it.
_LAUNCHER = """#!/usr/bin/env bash
set -euo pipefail

runfiles="${{RUNFILES_DIR:-}}"
if [[ -z "${{runfiles}}" ]]; then
  if [[ -d "$0.runfiles" ]]; then
    runfiles="$0.runfiles"
  elif [[ "$0" == *.runfiles/* ]]; then
    runfiles="${{0%%.runfiles/*}}.runfiles"
  else
    echo "cannot find the runfiles tree; run this with 'bazel run'" >&2
    exit 1
  fi
fi
export RUNFILES_DIR="${{runfiles}}"
root="${{runfiles}}/{workspace}"

# "$@" last, so `bazel run //:lib.publish -- --dry-run` works and a later flag wins.
exec "${{root}}/{publisher}" \\
  {repository} \\
  "--coordinates=${{root}}/{coordinates}" \\
  "--pom=${{root}}/{pom}" \\
  "--jar=${{root}}/{jar}" \\
  "$@"
"""

def _shell_quote(value):
    """Renders `value` as a single bash word that expands to exactly itself.

    The repository is the one thing in this launcher that comes from a BUILD file, and
    it goes into a generated shell script. Inside double quotes bash still expands `$`,
    backticks and `$(...)`, so a repository containing any of them would have part of
    itself replaced by the output of a command — and a `file:` repository is a
    filesystem path, which is exactly where a `$` or a space turns up.

    Single quotes suppress every expansion bash has. The only character they cannot
    contain is a single quote, which is closed, escaped and reopened in the usual way.
    """
    return "'" + value.replace("'", "'\\''") + "'"

def _clj_maven_publish_impl(ctx):
    export = ctx.attr.export[MavenExportInfo]

    # ctx.outputs.executable rather than a declared file: an executable rule already has
    # one predeclared under the target's own name, and Bazel expects that to be the file
    # it runs.
    launcher = ctx.outputs.executable

    ctx.actions.write(
        output = launcher,
        content = _LAUNCHER.format(
            workspace = ctx.workspace_name,
            publisher = ctx.executable._publisher.short_path,
            repository = _shell_quote("--repository=" + ctx.attr.repository),
            coordinates = export.coordinates.short_path,
            pom = export.pom.short_path,
            jar = export.jar.short_path,
        ),
        is_executable = True,
    )

    return [DefaultInfo(
        executable = launcher,
        runfiles = ctx.runfiles(
            files = [export.jar, export.pom, export.coordinates],
        ).merge(ctx.attr._publisher[DefaultInfo].default_runfiles),
    )]

_clj_maven_publish = rule(
    implementation = _clj_maven_publish_impl,
    doc = """Uploads a clj_maven_export's jar and pom. `bazel run` only.

    bazel run //:lib.publish                # to Clojars
    bazel run //:lib.publish -- --dry-run   # print the PUTs, send nothing

Reads CLOJARS_USERNAME and CLOJARS_PASSWORD (falling back to MAVEN_USER and
MAVEN_PASSWORD) from the environment of the process, not of the build. On Clojars the
password must be a deploy token.""",
    executable = True,
    attrs = {
        "export": attr.label(
            doc = "The clj_maven_export whose artifacts to publish.",
            providers = [MavenExportInfo],
            mandatory = True,
        ),
        "repository": attr.string(
            doc = """Where to PUT. Defaults to the Clojars DEPLOY endpoint.

Note that this is not the URL artifacts are read from — https://repo.clojars.org is a
read-only mirror and answers an upload with a 405. A `file:` URL installs into a local
repository instead, which is how you test the whole path without publishing:

    bazel run //:lib.publish -- --repository=file:///$HOME/.m2/repository""",
            default = "https://clojars.org/repo",
        ),
        "_publisher": attr.label(
            default = Label("//src/main/java/dev/palermo/rulesclj/maven:publisher"),
            executable = True,
            cfg = "exec",
        ),
    },
)

def clj_maven_export(
        name,
        group_id,
        artifact_id,
        version_file,
        deps_edn,
        srcs = [],
        resources = [],
        strip_prefix = "",
        description = "",
        url = "",
        scm_url = "",
        license = None,
        repository = "https://clojars.org/repo",
        **kwargs):
    """A publishable library: a jar, a pom, and a `.publish` target that uploads them.

    ```starlark
    clj_maven_export(
        name = "clj-grpc",
        group_id = "com.github.bpalermo",
        artifact_id = "clj-grpc",
        version_file = "version.edn",
        deps_edn = "deps.edn",
        srcs = glob(["src/**/*.clj"]),
        strip_prefix = "src",
        description = "gRPC for Clojure on non-shaded Netty.",
        url = "https://github.com/bpalermo/clj-grpc",
        scm_url = "https://github.com/bpalermo/clj-grpc",
        license = ("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    )
    ```

    produces two targets:

    | `//:clj-grpc` | the jar and the pom. Hermetic, cached, and worth building in CI. |
    | `//:clj-grpc.publish` | uploads them. `manual`, so `bazel build //...` never runs it. |

    The pom's dependencies come from the deps.edn's top-level `:deps` — not from Bazel's
    dependency graph. That is on purpose: a Clojure library's published contract is the
    set of coordinates its consumers must resolve, which is what the deps.edn states,
    while the Bazel graph holds jars pinned by digest from a lockfile. Deriving one from
    the other would publish the lockfile's resolution as if it were the library's
    requirements.

    Args:
      name: target name. The publish target is `{name}.publish`.
      group_id: Maven groupId.
      artifact_id: Maven artifactId. Also the pom's `<name>`.
      version_file: file holding the version — `{:version "1.2.3"}` or the bare string.
      deps_edn: the deps.edn whose top-level `:deps` become the pom's dependencies.
      srcs: Clojure sources to ship.
      resources: further files to ship verbatim.
      strip_prefix: prefix removed from each path, e.g. "src".
      description: the pom's `<description>`. Clojars shows it.
      url: the project's `<url>`.
      scm_url: repository URL; the `<scm>` block is derived from it.
      license: `(name, url)`, e.g. `("Apache-2.0", "https://...")`.
      repository: default deploy URL for the publish target.
      **kwargs: common attributes — visibility, tags — passed to the export target.
    """
    if license and len(license) != 2:
        fail("clj_maven_export: license must be (name, url), got: {}".format(license))

    _clj_maven_export(
        name = name,
        group_id = group_id,
        artifact_id = artifact_id,
        version_file = version_file,
        deps_edn = deps_edn,
        srcs = srcs,
        resources = resources,
        strip_prefix = strip_prefix,
        description = description,
        url = url,
        scm_url = scm_url,
        license_name = license[0] if license else "",
        license_url = license[1] if license else "",
        **kwargs
    )

    _clj_maven_publish(
        name = name + ".publish",
        export = name,
        repository = repository,
        # Manual, because publishing is not a build step. Without this, `bazel build
        # //...` builds a launcher nobody asked for, and — worse — someone eventually
        # wires it into a target that gets run.
        tags = ["manual"],
        visibility = kwargs.get("visibility"),
        # testonly follows the export it publishes. Bazel forbids a non-testonly target
        # depending on a testonly one, so without this a testonly export produces a
        # publish target that cannot be analysed at all — which is how this ruleset's
        # own tests found it.
        testonly = kwargs.get("testonly"),
    )
