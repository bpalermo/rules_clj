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
# Two synthetic projects of different sizes, and the cost of a compile taken as the
# SLOPE between them.
#
# Whole-build wall clock was tried first and does not answer the question: a clean build
# also rebuilds the shim, the worker launcher and the JDK's own bits, and at these sizes
# those fixed costs are most of the number — one configuration came out at 4s for 40
# fresh JVMs, which is impossible. Differencing two sizes cancels everything that does
# not scale with the number of namespaces.
#
# --jobs=1 so the figure is the cost of an action rather than of this machine's core
# count, and the caches are off so the builds actually happen.
# ---------------------------------------------------------------------------------
small=$((count / 4))
[ "$small" -lt 5 ] && small=5

generate() {
    local dir="$1" n="$2" i prev deps
    mkdir -p "$dir/src/bench"
    cat >"$dir/MODULE.bazel" <<EOF
module(name = "rules_clj_benchmark", version = "0.0.0")
bazel_dep(name = "rules_clj", version = "0.0.0")
local_path_override(module_name = "rules_clj", path = "$repo")
EOF
    cat >"$dir/.bazelrc" <<'EOF'
build --java_language_version=21
build --java_runtime_version=remotejdk_21
build --tool_java_language_version=21
build --tool_java_runtime_version=remotejdk_21
EOF
    echo 'load("@rules_clj//clojure:defs.bzl", "clj_library")' >"$dir/src/bench/BUILD.bazel"
    for i in $(seq 1 "$n"); do
        if [ "$i" -eq 1 ]; then
            printf '(ns bench.ns%s)\n(defn value [] %s)\n' "$i" "$i" >"$dir/src/bench/ns$i.clj"
            deps=""
        else
            prev=$((i - 1))
            printf '(ns bench.ns%s (:require [bench.ns%s :as prev]))\n(defn value [] (+ %s (prev/value)))\n' \
                "$i" "$prev" "$i" >"$dir/src/bench/ns$i.clj"
            deps="\":ns$prev\""
        fi
        cat >>"$dir/src/bench/BUILD.bazel" <<EOF
clj_library(name = "ns$i", srcs = ["ns$i.clj"], namespaces = ["bench.ns$i"], strip_prefix = "src", deps = [$deps])
EOF
    done
}

generate "$work/small" "$small"
generate "$work/large" "$count"

# Disabling the caches matters more than it looks: without it a second build after
# `bazel clean` is served from the user's disk cache, every configuration finishes in
# about two seconds, and nothing has compiled.
NO_CACHE=(--disk_cache= --noremote_accept_cached --noremote_upload_local_results --jobs=1)

time_build() {
    local dir="$1"
    shift
    local start end
    (cd "$dir" && bazel clean >/dev/null 2>&1 && bazel build //... "${NO_CACHE[@]}" "$@" >/dev/null 2>&1)
    (cd "$dir" && bazel clean >/dev/null 2>&1)
    start=$(date +%s.%N)
    (cd "$dir" && bazel build //... "${NO_CACHE[@]}" "$@" >/dev/null 2>&1) || { echo "build failed in $dir" >&2; exit 1; }
    end=$(date +%s.%N)
    echo "$end - $start" | bc
}

per_action() {
    local label="$1"
    shift
    local t_small t_large
    t_small="$(time_build "$work/small" "$@")"
    t_large="$(time_build "$work/large" "$@")"
    printf '  %-34s %ss per compile\n' "$label" \
        "$(echo "scale=3; ($t_large - $t_small) / ($count - $small)" | bc)"
}

echo "cost of one compile action, as the slope between ${small} and ${count} namespaces:"
per_action "a JVM per action" --@rules_clj//clojure:worker=false
per_action "persistent worker (the default)" --@rules_clj//clojure:worker=true
echo
echo "The CDS archive is not measured here. It is off by default for a structural reason"
echo "rather than a timing one — being uncacheable, it changes every compile action's key"
echo "on every clean build — so a number would not change the decision. docs/design.md."
