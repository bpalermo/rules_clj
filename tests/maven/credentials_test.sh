#!/usr/bin/env bash
#
# Which credentials the publisher picks, and — more to the point — which it refuses to
# invent.
#
# A subprocess test rather than an in-process one, because the thing under test IS the
# process environment, and a JVM cannot honestly change its own. Each case therefore runs
# the real launcher with a controlled environment.
#
# The repository is overridden to a port nothing listens on, on loopback. The launcher
# puts "$@" last so a later flag wins, so this cannot reach Clojars however the target was
# configured — which matters in a test that deliberately sets credential variables.
#
# The distinction each case draws is between "refused before any request" and "got as far
# as trying to connect", and those are far enough apart in the output to tell apart.

set -euo pipefail

srcdir="${TEST_SRCDIR:?TEST_SRCDIR is set by Bazel for every test}"
workspace="${TEST_WORKSPACE:?TEST_WORKSPACE is set by Bazel for every test}"
launcher="${srcdir}/${workspace}/${1:?usage: credentials_test.sh <publish>}"

# Nothing listens here, and it is this machine, so no request leaves it.
DEAD="https://127.0.0.1:1/repo"

failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# The launcher, with all four credential variables cleared and only the named ones set.
publish_with() {
  env -u CLOJARS_USERNAME -u CLOJARS_PASSWORD -u MAVEN_USER -u MAVEN_PASSWORD \
    "$@" "${launcher}" "--repository=${DEAD}" 2>&1 || true
}

# --- the mixing this is here to prevent -------------------------------------
#
# Choosing the username and the password independently means a half-configured Clojars
# pair borrows the Maven password: one repository's secret, sent to another repository's
# server, under the first one's username. The server answers 401, so the reader concludes
# the token is wrong rather than that it went somewhere it should never have been.

output="$(publish_with CLOJARS_USERNAME=clojars-user MAVEN_PASSWORD=maven-secret)"
if ! grep -q "no credentials" <<<"${output}"; then
  fail "a half-configured Clojars pair was completed from the Maven pair. Output was:
${output}"
fi
if ! grep -q "CLOJARS_PASSWORD is not set" <<<"${output}"; then
  fail "the refusal did not name the missing half. Output was:
${output}"
fi

output="$(publish_with CLOJARS_PASSWORD=clojars-secret MAVEN_USER=maven-user)"
if ! grep -q "no credentials" <<<"${output}"; then
  fail "a half-configured pair was completed the other way round. Output was:
${output}"
fi

# --- the pairs that ARE complete --------------------------------------------
#
# Both must get past the credential check. They then fail to connect, which is the proof
# they got that far: the refusal above happens before any request is made.

for pair in "CLOJARS_USERNAME=u CLOJARS_PASSWORD=p" "MAVEN_USER=u MAVEN_PASSWORD=p"; do
  # shellcheck disable=SC2086  # two VAR=VALUE words for env, deliberately unquoted.
  output="$(publish_with ${pair})"
  if grep -q "no credentials" <<<"${output}"; then
    fail "a complete pair (${pair}) was not accepted. Output was:
${output}"
  fi
done

# --- none at all ------------------------------------------------------------

output="$(publish_with)"
if ! grep -q "no credentials" <<<"${output}"; then
  fail "an empty environment did not produce the credentials message. Output was:
${output}"
fi
if grep -q "is not set, though" <<<"${output}"; then
  fail "nothing was half-configured, so nothing should have been named. Output was:
${output}"
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "${failures} case(s) failed" >&2
  exit 1
fi
echo "PASS: credentials are taken as a pair"
