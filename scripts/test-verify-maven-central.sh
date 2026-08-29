#!/usr/bin/env bash
# Offline tests for scripts/verify-maven-central.sh. A fake mvn on PATH records its
# invocations instead of contacting Maven Central, and TMPDIR is redirected at a scratch
# directory so the throwaway repository the script creates (and deletes on exit) is fully
# contained. Run from anywhere: bash scripts/test-verify-maven-central.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="$script_dir/verify-maven-central.sh"
consumer_dir="$(cd "$script_dir/../verification/maven-central-consumer" && pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/verify-maven-central-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fakebin="$work/fakebin"
mkdir -p "$fakebin"
cat > "$fakebin/mvn" <<'EOF'
#!/usr/bin/env bash
echo "MAVEN_BASEDIR=${MAVEN_BASEDIR:-}" >> "$FAKE_MVN_LOG"
echo "args:$*" >> "$FAKE_MVN_LOG"
exit "${FAKE_MVN_EXIT:-0}"
EOF
cat > "$fakebin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$fakebin/mvn" "$fakebin/sleep"

export FAKE_MVN_LOG="$work/mvn.log"
export TMPDIR="$work/tmpdir"
mkdir -p "$TMPDIR"
export PATH="$fakebin:$PATH"

failures=0
pass() { printf 'ok   - %s\n' "$1"; }
fail() { printf 'FAIL - %s\n' "$1" >&2; failures=$((failures + 1)); }

leftover_repos() {
  find "$TMPDIR" -maxdepth 1 -name 'verify-maven-central.*' -print
}

expect_rejected() {
  local input="$1" code
  : > "$FAKE_MVN_LOG"
  set +e
  bash "$target" "$input" > "$work/out" 2>&1
  code=$?
  set -e
  if [ "$code" -ne 2 ]; then
    fail "version '$input': expected exit 2, got $code"
  elif [ -s "$FAKE_MVN_LOG" ]; then
    fail "version '$input': mvn ran despite the rejection"
  elif [ -n "$(leftover_repos)" ]; then
    fail "version '$input': a throwaway repository leaked"
  else
    pass "version '$input' rejected before any mvn run"
  fi
}

for hostile in '../foo' '1.0/../../x' '0.3.0/../..' '..' '.' 'a/b' 'a\b' '1.0 SNAPSHOT' '-rf'; do
  expect_rejected "$hostile"
done

# Happy path: the published artifact resolves on the first attempt.
: > "$FAKE_MVN_LOG"
set +e
bash "$target" "0.3.0-SNAPSHOT" > "$work/out" 2>&1
code=$?
set -e
if [ "$code" -ne 0 ]; then
  fail "happy path: expected exit 0, got $code"
elif [ "$(grep -c '^args:' "$FAKE_MVN_LOG")" -ne 1 ]; then
  fail "happy path: expected exactly one mvn invocation"
elif ! grep -qF -- "-f $consumer_dir/pom.xml" "$FAKE_MVN_LOG"; then
  fail "happy path: mvn did not build the consumer project"
elif ! grep -qF -- "MAVEN_BASEDIR=$consumer_dir" "$FAKE_MVN_LOG"; then
  fail "happy path: MAVEN_BASEDIR does not point at the consumer project"
elif ! grep -qF -- "-Dparallel-in-scope.version=0.3.0-SNAPSHOT" "$FAKE_MVN_LOG"; then
  fail "happy path: version not passed through to the consumer build"
elif ! grep -qE -- "-Dmaven\.repo\.local=$TMPDIR/verify-maven-central\.[^ ]*" "$FAKE_MVN_LOG"; then
  fail "happy path: mvn did not resolve into a throwaway repository under TMPDIR"
elif [ -n "$(leftover_repos)" ]; then
  fail "happy path: throwaway repository leaked past exit"
else
  pass "valid version resolves once from a throwaway repository and cleans it up"
fi

# The default applies to a missing or empty argument.
: > "$FAKE_MVN_LOG"
set +e
bash "$target" "" > "$work/out" 2>&1
code=$?
set -e
if [ "$code" -eq 0 ] && grep -qF -- "-Dparallel-in-scope.version=0.3.0-SNAPSHOT" "$FAKE_MVN_LOG"; then
  pass "empty argument falls back to the default snapshot version"
else
  fail "empty argument: expected exit 0 with the default snapshot version, got $code"
fi

# Exhausted retries: mvn fails all 12 attempts and the script gives up.
: > "$FAKE_MVN_LOG"
set +e
FAKE_MVN_EXIT=1 bash "$target" "0.3.0-SNAPSHOT" > "$work/out" 2>&1
code=$?
set -e
if [ "$code" -ne 1 ]; then
  fail "failing mvn: expected exit 1, got $code"
elif [ "$(grep -c '^args:' "$FAKE_MVN_LOG")" -ne 12 ]; then
  fail "failing mvn: expected 12 mvn invocations"
elif [ -n "$(leftover_repos)" ]; then
  fail "failing mvn: throwaway repository leaked past exit"
else
  pass "all 12 attempts run and give up with exit 1, repository cleaned up"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures test(s) failed" >&2
  exit 1
fi
echo "all tests passed"
