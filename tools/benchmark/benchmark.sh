#!/usr/bin/env bash
# Measures what compiling Clojure through these rules actually costs.
#
# This exists because the ruleset makes a falsifiable claim: that a class data sharing
# archive makes JVM startup cheap enough that a persistent worker is not needed. A claim
# like that is worth only as much as the number behind it, and the number changes with
# the JDK, the machine and the size of the project — so it is measured here rather than
# asserted in a document.
#
# Not a Bazel test: timings under CI runners are noise, and a flaky test that measures
# performance teaches people to ignore test failures.
#
#   tools/benchmark/benchmark.sh [namespace-count]

set -o errexit -o nounset -o pipefail

count="${1:-50}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "rules_clj benchmark — ${count} namespaces"
echo "repo:  $repo"
echo

# ---------------------------------------------------------------------------------
# A synthetic project. Each namespace requires the one before it, so the graph has real
# depth rather than being N independent compiles: dependency loading is part of what is
# being measured.
# ---------------------------------------------------------------------------------
mkdir -p "$work/src/bench"
cat >"$work/MODULE.bazel" <<EOF
module(name = "rules_clj_benchmark", version = "0.0.0")
bazel_dep(name = "rules_clj", version = "0.0.0")
local_path_override(module_name = "rules_clj", path = "$repo")
EOF
cat >"$work/.bazelrc" <<'EOF'
build --java_language_version=21
build --java_runtime_version=remotejdk_21
build --tool_java_language_version=21
build --tool_java_runtime_version=remotejdk_21
EOF

{
    echo 'load("@rules_clj//clojure:defs.bzl", "clj_library")'
    echo ''
} >"$work/src/bench/BUILD.bazel"

for i in $(seq 1 "$count"); do
    if [ "$i" -eq 1 ]; then
        cat >"$work/src/bench/ns$i.clj" <<EOF
(ns bench.ns$i)
(defn value [] $i)
EOF
        deps=""
    else
        prev=$((i - 1))
        cat >"$work/src/bench/ns$i.clj" <<EOF
(ns bench.ns$i (:require [bench.ns$prev :as prev]))
(defn value [] (+ $i (prev/value)))
EOF
        deps="\":ns$prev\""
    fi
    cat >>"$work/src/bench/BUILD.bazel" <<EOF
clj_library(
    name = "ns$i",
    srcs = ["ns$i.clj"],
    namespaces = ["bench.ns$i"],
    strip_prefix = "src",
    deps = [$deps],
)
EOF
done

# ---------------------------------------------------------------------------------
# The CDS claim, measured where it actually applies.
#
# An earlier version of this timed the JVM directly, outside Bazel. That cannot work: an
# archive records the classpath it was dumped with, and inside a sandbox those paths are
# relative, so an outside invocation with absolute paths never matches it — the probe
# measured "no archive" twice and reported the difference as a result. Toggling the
# ruleset's own flag keeps everything else identical.
# ---------------------------------------------------------------------------------
cd "$work"

# Each configuration is built twice and the second is reported. The first build of a
# session warms the page cache for the JDK and the jars, which is worth several seconds
# — enough that whichever configuration ran first looked dramatically worse.
time_build() {
    local flag="$1" start end
    bazel clean >/dev/null 2>&1
    bazel build //... "$flag" >/dev/null 2>&1
    bazel clean >/dev/null 2>&1
    start=$(date +%s.%N)
    bazel build //... "$flag" >/dev/null 2>&1
    end=$(date +%s.%N)
    echo "$end - $start" | bc
}

off="$(time_build --@rules_clj//clojure:cds=off)"
on="$(time_build --@rules_clj//clojure:cds=auto)"
saved=$(echo "scale=1; 100 * ($off - $on) / $off" | bc)

echo "cold build of ${count} targets:"
echo "  --@rules_clj//clojure:cds=off   ${off}s"
echo "  --@rules_clj//clojure:cds=auto  ${on}s   (${saved}% faster)"
echo

# An archive that is silently ignored looks exactly like one that works, so require it.
if bazel build //... --@rules_clj//clojure:cds=on >/dev/null 2>&1; then
    echo "the archive is genuinely mapped (-Xshare:on succeeds in every compile action)"
else
    echo "WARNING: builds fail under cds=on, so the archive is NOT being mapped." >&2
    echo "  Builds still succeed by default — cds=auto degrades quietly — but the saving" >&2
    echo "  above is not being had. The usual cause is the compile classpath no longer" >&2
    echo "  starting with exactly the prefix the archive was dumped with; see" >&2
    echo "  clojure/private/cds.bzl." >&2
    exit 1
fi
