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

# Checked against the ARCHIVE rather than the working tree, deliberately: this is the
# file BCR will read, and a mismatch caught here is far cheaper than the same mismatch
# reported by the registry after a tag exists.
#
# The parser comes out of the archive too, not from the checkout. It is the same script
# either way when the tag matches the working tree, and when it does not — a re-run
# against an older tag, a dispatch from a different ref — the archived tree is what is
# being shipped, so the archived tree is what should be judging itself. That also removes
# the dependence on $PWD: everything runs from inside the unpacked prefix.
unpacked="$(mktemp -d)"
trap 'rm -rf "${unpacked}"' EXIT
tar -xzf "${ARCHIVE}" -C "${unpacked}" \
    "${PREFIX}/MODULE.bazel" \
    "${PREFIX}/bazel/tools/workspace_status.sh"
declared=$(cd "${unpacked}/${PREFIX}" && ./bazel/tools/workspace_status.sh |
    awk '$1 == "STABLE_VERSION" { print $2 }')

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

# A note to whoever cuts the release, printed where they will see it rather than
# buried in a workflow comment.
cat >&2 <<'REMINDER'

  Reminder: the Bazel Central Registry pull request is NOT opened automatically.
  PUBLISH_TOKEN is a fine-grained PAT scoped to the registry fork, and GitHub does
  not permit those to open pull requests against repositories they cannot access.
  The publish job prints a URL — open it to finish the release.

REMINDER
