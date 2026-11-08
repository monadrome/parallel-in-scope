#!/usr/bin/env bash
set -euo pipefail

sample_id=${1:-BUG-0001}
candidate_patch=${2:-}
if [[ $# -gt 2 ]]; then
  echo "usage: $0 [sample-id] [candidate.patch]" >&2
  exit 2
fi

repo_root=$(git rev-parse --show-toplevel)
sample_dir="$repo_root/historical-eval/samples/$sample_id"
if [[ ! -f "$sample_dir/manifest.json" ]]; then
  echo "unknown sample: $sample_id" >&2
  exit 2
fi
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/historical-eval-XXXXXX")
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

buggy_commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["buggy_commit"])' "$sample_dir/manifest.json")
test_class=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["test_class"])' "$sample_dir/manifest.json")

git archive "$buggy_commit" | tar -x -C "$work_dir"
while IFS= read -r -d '' test_file; do
  target="$work_dir/src/test/java/${test_file#"$sample_dir/oracle/hidden-tests/"}"
  mkdir -p "$(dirname "$target")"
  cp "$test_file" "$target"
done < <(find "$sample_dir/oracle/hidden-tests" -type f -print0)

if [[ -n "$candidate_patch" ]]; then
  candidate_patch=$(cd "$(dirname "$candidate_patch")" && pwd)/$(basename "$candidate_patch")
  git -C "$work_dir" apply --check "$candidate_patch"
  git -C "$work_dir" apply "$candidate_patch"
fi

mvn -q -f "$work_dir/pom.xml" -Dtest="$test_class" test
