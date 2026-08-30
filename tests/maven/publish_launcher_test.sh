#!/usr/bin/env bash
#
# The generated publish launcher, run four ways.
#
# The launcher is fifteen lines of Starlark string formatting around a runfiles lookup,
# and that is exactly the kind of code that cannot be checked by reading it: it is either
# right or it fails at the moment someone tries to cut a release. So this runs it.
#
# Three of the four cases are the situations it is actually started in —
#
#   RUNFILES_DIR set        `bazel run //:lib.publish`, and this test
#   a sibling .runfiles/    started by path, the way a release script does it
#   inside a .runfiles/     started by path from within someone else's runfiles tree
#
# — and the fourth is the case where none of them holds, where the requirement is that
# it says so rather than reaching for a path that does not exist.
#
# It also pins ctx.workspace_name against Bazel's own TEST_WORKSPACE. That value is
# "_main" under bzlmod and the launcher's every path is built on it; a Bazel release
# that changed it would break publishing silently, in the one command that has no test
# run before it in anger.
#
# --dry-run throughout: the launcher's job is to find four files and exec one binary,
# and none of that needs the network.

set -euo pipefail

srcdir="${TEST_SRCDIR:?TEST_SRCDIR is set by Bazel for every test}"
workspace="${TEST_WORKSPACE:?TEST_WORKSPACE is set by Bazel for every test}"
launcher="${srcdir}/${workspace}/${1:?usage: publish_launcher_test.sh <rootpath of the publish target>}"

failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# Runs the launcher with every runfiles-related variable removed from the environment,
# so that what is left is the script's own resolution rather than something Bazel put
# there. JAVA_RUNFILES and RUNFILES_MANIFEST_FILE go too: the publisher this execs is a
# java_binary whose own stub reads them, and leaving them set would let the launcher
# resolve nothing and still appear to work.
bare() {
  env -u RUNFILES_DIR -u RUNFILES_MANIFEST_FILE -u RUNFILES_MANIFEST_ONLY \
    -u JAVA_RUNFILES -u TEST_SRCDIR "$@"
}

# Every case must print the same plan: ten files, the jar first, nothing sent.
expect_dry_run_plan() {
  local case_name="$1" output="$2"
  local base="https://clojars.org/repo/com/github/bpalermo/clj-grpc/0.1.4"

  if ! grep -Fq "PUT ${base}/clj-grpc-0.1.4.jar (" <<<"${output}"; then
    fail "${case_name}: no PUT of the jar. Output was:
${output}"
    return
  fi
  if ! grep -Fq "PUT ${base}/clj-grpc-0.1.4.pom (" <<<"${output}"; then
    fail "${case_name}: no PUT of the pom. Output was:
${output}"
    return
  fi
  if ! grep -Fq "10 files would be uploaded; nothing was sent." <<<"${output}"; then
    fail "${case_name}: the run did not end with the dry-run summary. Output was:
${output}"
  fi
}

if [[ ! -x "${launcher}" ]]; then
  echo "FAIL: ${launcher} is not an executable file; the sh_test's data is wrong" >&2
  exit 1
fi

# --- ctx.workspace_name -------------------------------------------------------
#
# Read out of the generated file rather than inferred from behaviour, so that a
# mismatch names the value instead of producing a confusing missing-file error.

echo "== the launcher's workspace name is Bazel's =="
if ! grep -Fq "root=\"\${runfiles}/${workspace}\"" "${launcher}"; then
  fail "the launcher does not build its paths on TEST_WORKSPACE (${workspace}). It contains:
$(grep -F 'root=' "${launcher}" || true)"
fi

# --- case 1: RUNFILES_DIR, which is what `bazel run` sets ---------------------

echo "== RUNFILES_DIR is set =="
if output="$(RUNFILES_DIR="${srcdir}" "${launcher}" --dry-run 2>&1)"; then
  expect_dry_run_plan "RUNFILES_DIR" "${output}"
else
  fail "RUNFILES_DIR: the launcher exited $?. Output was:
${output}"
fi

# --- case 2: started by path, from inside a runfiles tree ---------------------
#
# TEST_SRCDIR is itself a .runfiles directory, so the launcher's own path exercises the
# ${0%%.runfiles/*} branch with no staging at all.

echo "== no RUNFILES_DIR, and the path runs through a .runfiles directory =="
if [[ "${launcher}" != *.runfiles/* ]]; then
  fail "TEST_SRCDIR (${srcdir}) is not a .runfiles directory, so this case cannot run"
elif output="$(bare "${launcher}" --dry-run 2>&1)"; then
  expect_dry_run_plan "inside .runfiles" "${output}"
else
  fail "inside .runfiles: the launcher exited $?. Output was:
${output}"
fi

# --- case 3: started by path, with a sibling .runfiles ------------------------
#
# The shape a release script sees: bazel-bin/pkg/lib.publish next to
# bazel-bin/pkg/lib.publish.runfiles. Staged as a copy plus a symlink, because the real
# pair is not reachable from inside a sandboxed test.

echo "== no RUNFILES_DIR, with a sibling .runfiles directory =="
staged="$(mktemp -d "${TEST_TMPDIR:-/tmp}/sibling.XXXXXX")"
cp "${launcher}" "${staged}/lib.publish"
ln -s "${srcdir}" "${staged}/lib.publish.runfiles"
if output="$(bare "${staged}/lib.publish" --dry-run 2>&1)"; then
  expect_dry_run_plan "sibling .runfiles" "${output}"
else
  fail "sibling .runfiles: the launcher exited $?. Output was:
${output}"
fi

# --- case 4: no runfiles anywhere ---------------------------------------------

echo "== no runfiles at all =="
orphan="$(mktemp -d "${TEST_TMPDIR:-/tmp}/orphan.XXXXXX")"
cp "${launcher}" "${orphan}/lib.publish"
status=0
output="$(bare "${orphan}/lib.publish" --dry-run 2>&1)" || status=$?
if [[ "${status}" -eq 0 ]]; then
  fail "no runfiles: the launcher succeeded, which it cannot have done honestly. Output was:
${output}"
elif ! grep -Fq "cannot find the runfiles tree" <<<"${output}"; then
  fail "no runfiles: the launcher failed without saying why. Output was:
${output}"
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "${failures} case(s) failed" >&2
  exit 1
fi
echo "all four cases resolved the runfiles tree"
