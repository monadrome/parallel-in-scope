#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "usage: $0 [candidate.patch]" >&2
  exit 2
fi

repo_root=$(git rev-parse --show-toplevel)
sample_dir="$repo_root/historical-eval/samples/BUG-0001"
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/historical-eval-XXXXXX")
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

buggy_commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["buggy_commit"])' "$sample_dir/manifest.json")

git archive "$buggy_commit" | tar -x -C "$work_dir"
mkdir -p "$work_dir/src/test/java/io/github/monadrome/parallelinscope"
cp "$sample_dir/oracle/hidden-tests/io/github/monadrome/parallelinscope/DeadlinesGoldenTest.java" \
  "$work_dir/src/test/java/io/github/monadrome/parallelinscope/DeadlinesGoldenTest.java"

if [[ $# -eq 1 ]]; then
  candidate_patch=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
  git -C "$work_dir" apply --check "$candidate_patch"
  git -C "$work_dir" apply "$candidate_patch"
fi

mvn -q -f "$work_dir/pom.xml" -Dtest=DeadlinesGoldenTest test
