#!/usr/bin/env bash
# Workspace status: the single place the version is parsed.
#
# Bazel runs this on every build, so it must be fast and must never fail — a non-zero
# exit breaks the build. Everything here degrades to a placeholder instead.
#
# MODULE.bazel is the source of truth and cannot be anything else. Its version is read
# during module resolution, before any build runs, so there is no stamping, no make
# variable and no workspace status that could supply it; and the Bazel Central Registry
# validates that the version in a published entry matches the literal in the archive's
# MODULE.bazel. What stamping can do is stop that literal being parsed in three places —
# here, the release workflow, and release_prep.sh — because three copies of a version
# regex drift, and the symptom only appears when cutting a release.
#
# STABLE_ keys are part of the action cache key, so a version change re-stamps anything
# that consumes them. Unprefixed keys are volatile and can keep a stale value from an
# earlier build, which is precisely wrong for something identifying an artifact.
set -uo pipefail

version=$(grep -oE '^ *version = "[^"]+"' MODULE.bazel 2>/dev/null |
    head -1 | sed -E 's/.*"([^"]+)".*/\1/')
echo "STABLE_VERSION ${version:-0.0.0-unknown}"

commit=$(git rev-parse --short=12 HEAD 2>/dev/null)
echo "STABLE_GIT_COMMIT ${commit:-unknown}"
