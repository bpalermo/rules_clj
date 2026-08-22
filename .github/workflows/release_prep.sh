#!/usr/bin/env bash
# Prepares the release archive. Called by bazel-contrib/.github's release_ruleset with the
# tag as its only argument; stdout becomes the release notes.
#
# The path is hard-coded upstream on purpose: release_ruleset takes no command input,
# because a dispatch-supplied command would not be covered by the attestation. That is
# also why BCR only trusts archives produced this way.

set -o errexit -o nounset -o pipefail

TAG="${1:?tag, e.g. v0.1.0}"
PREFIX="rules_clj-${TAG#v}"
ARCHIVE="rules_clj-${TAG}.tar.gz"

git archive --format=tar --prefix="${PREFIX}/" "${TAG}" | gzip > "${ARCHIVE}"

# Checked rather than trusted: a mismatch here is caught now, not by BCR much later with a
# far less obvious message.
declared=$(tar -xzOf "${ARCHIVE}" "${PREFIX}/MODULE.bazel" \
  | grep -oE 'version = "[^"]+"' | head -1 | cut -d'"' -f2)
if [ "${declared}" != "${TAG#v}" ]; then
  echo "archived MODULE.bazel declares '${declared}', tag is '${TAG#v}'" >&2
  exit 1
fi

cat <<NOTES
## Install

\`\`\`starlark
bazel_dep(name = "rules_clj", version = "${TAG#v}")
\`\`\`

\`\`\`starlark
load("@rules_clj//clojure:defs.bzl", "clj_binary", "clj_library", "clj_test")
\`\`\`

A Clojure runtime comes with the rules, pinned by digest. Dependencies come from your
\`deps.edn\` via a lockfile (\`bazel run @rules_clj//tools/lock -- --deps-edn=...\`), so
builds need no Clojure CLI, no \`~/.m2\`, and no resolution at build time.

## JDK

The compiler runs on Bazel's **tool** JVM and needs Java 21:

\`\`\`
build --java_language_version=21
build --java_runtime_version=remotejdk_21
build --tool_java_language_version=21
build --tool_java_runtime_version=remotejdk_21
\`\`\`

Tested with Bazel 8.x and 9.x on Linux and macOS.
NOTES
