#!/usr/bin/env bash
set -euo pipefail

sample_id=${1:-BUG-0001}
candidate_patch=${2:-}
use_fixed=${HISTORICAL_EVAL_USE_FIXED:-0}
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
private_root=${HISTORICAL_EVAL_PRIVATE_DIR:-"$HOME/.cache/vformation/historical-eval-private"}
hidden_dir="$private_root/samples/$sample_id/hidden-tests"
if [[ ! -d "$hidden_dir" ]]; then
  echo "missing evaluator-only hidden tests: $hidden_dir" >&2
  exit 2
fi
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/historical-eval-XXXXXX")
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

buggy_commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["buggy_commit"])' "$sample_dir/manifest.json")
fixed_commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fixed_commit"])' "$sample_dir/manifest.json")
test_class=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["test_class"])' "$sample_dir/manifest.json")
production_files=()
while IFS= read -r path; do
  production_files+=("$path")
done < <(python3 -c 'import json,sys; print("\\n".join(json.load(open(sys.argv[1]))["production_files"]))' "$sample_dir/manifest.json")

git archive "$buggy_commit" | tar -x -C "$work_dir"
while IFS= read -r -d '' test_file; do
  relative=${test_file#"$hidden_dir/"}
  target="$work_dir/src/test/java/$relative"
  mkdir -p "$(dirname "$target")"
  cp "$test_file" "$target"
done < <(find "$hidden_dir" -type f -print0)

# The fixed patch is derived inside the evaluator's temporary directory. It is
# never stored in the sample tree or mounted into an LLM workspace.
fixed_patch="$work_dir/fixed.patch"
git diff --binary "$buggy_commit" "$fixed_commit" -- "${production_files[@]}" > "$fixed_patch"

if [[ -n "$candidate_patch" && "$use_fixed" == "1" ]]; then
  echo "choose either a candidate patch or HISTORICAL_EVAL_USE_FIXED=1" >&2
  exit 2
fi

if [[ -n "$candidate_patch" ]]; then
  candidate_patch=$(cd "$(dirname "$candidate_patch")" && pwd)/$(basename "$candidate_patch")
  git -C "$work_dir" apply --check "$candidate_patch"
  git -C "$work_dir" apply "$candidate_patch"
elif [[ "$use_fixed" == "1" ]]; then
  git -C "$work_dir" apply --check "$fixed_patch"
  git -C "$work_dir" apply "$fixed_patch"
fi

mvn -q -f "$work_dir/pom.xml" -Dtest="$test_class" test
