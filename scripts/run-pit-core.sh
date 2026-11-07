#!/usr/bin/env bash
# Run the documented core-scope PIT partition against the current checkout.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run-pit-core.sh [options]

Run the core PIT audit partition for the root package. The queue subpackage is
excluded by the explicit class patterns, and VOID_METHOD_CALLS is always part of
the mutator set. Results are archived per batch under OUT/batch-<name>/:

  mutations.xml  PIT's raw mutation report
  pit.log        complete Maven/PIT console log
  run-meta.txt   revision, working-tree status, JDK, command, and timestamps

Options:
  --batch A|B|C|D|E|all  Run one batch or all five (default: all).
  --out DIR              Archive directory. Default: tmp/pit-audit-<UTC-date>.
  --force                Replace existing artifacts for the selected batches.
  --help                 Show this help and exit.

The command is offline (mvn -o) and runs from the repository root. It does not
run a release build or use PIT history input.
EOF
}

die() {
  printf 'run-pit-core: %s\n' "$1" >&2
  exit "${2:-1}"
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

batch="all"
out_dir=""
force=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --batch)
      [[ $# -ge 2 ]] || die "--batch requires A, B, C, D, E, or all" 2
      batch="$2"
      shift 2
      ;;
    --out)
      [[ $# -ge 2 && -n "$2" ]] || die "--out requires a directory" 2
      out_dir="$2"
      shift 2
      ;;
    --force)
      force=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1" 2
      ;;
  esac
done

case "$batch" in
  A|B|C|D|E|all) ;;
  *) die "--batch must be A, B, C, D, E, or all" 2 ;;
esac

if [[ -z "$out_dir" ]]; then
  out_dir="tmp/pit-audit-$(date -u +%Y%m%d)"
fi

package="io.github.monadrome.parallelinscope"
mutators="CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS"

# Keep these patterns aligned with verification/pit-audit/2026-10-31/inventory.md.
# Nested classes are included with *, while exact entries prevent a wildcard from
# crossing into the next batch.
batch_classes() {
  case "$1" in
    A)
      printf '%s' "$package.TaskId*,$package.ParId*,$package.TaskKey*,$package.TaskType*,$package.TaskTypeMismatches*,$package.TaskOutcome*,$package.ExecutionPhase,$package.UnitSpec*,$package.Validation*,$package.BatchOptions*,$package.TaskOptions*,$package.ImmediateResult*,$package.TaskObservation*,$package.ExecutorWaitReport*,$package.ParRuntimeSnapshot*,$package.WorkloadFitReport*,$package.ThreadPoolStarvationReport*,$package.WorkloadClock*,$package.ExecutorIdentity*"
      ;;
    B)
      printf '%s' "$package.CancellationToken*,$package.Deadlines*,$package.LeanCancellationException*,$package.TokenOutcomes*,$package.TaskExecutionState*,$package.ExecutionExitTracker*,$package.ExecutionPhaseHintFuture*,$package.TaskFuture,$package.TaskCompletion*"
      ;;
    C)
      printf '%s' "$package.TaskGroup*,$package.GroupDraft*,$package.GroupStart*,$package.GroupStep*,$package.GroupValues*,$package.CombinedGroupStep*,$package.Combiner*,$package.TaskBatch*,$package.BatchCombinedResult*,$package.MultiTaskContext*,$package.Checkpoints*,$package.Announcements*,$package.AdoptedSource*"
      ;;
    D)
      printf '%s' "$package.Par,$package.Par\$1,$package.ParRuntime*,$package.SlidingWindowSubmitter*,$package.TaskSubmissions*,$package.SubmissionScope*,$package.SubmissionException*"
      ;;
    E)
      printf '%s' "$package.Task,$package.TaskUnit*,$package.TaskNode*,$package.TaskExecutionContext*,$package.ScopedCallable*,$package.SubmissionAwareBlockingQueue*,$package.SystemWorkloadClock*,$package.WorkloadFit,$package.WorkloadFit\$1,$package.WorkloadFit\$Entry,$package.WorkloadFit\$Timing,$package.WorkloadFit\$Totals,$package.FutureInspector*,$package.ExecutorRuntime*,$package.ExecutorWaitGraph*,$package.ThreadPoolStarvationObservationScope*"
      ;;
  esac
}

utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

if [[ "$batch" == "all" ]]; then
  selected=(A B C D E)
else
  selected=("$batch")
fi

if [[ -e "$out_dir" && ! -d "$out_dir" ]]; then
  die "output path exists and is not a directory: $out_dir"
fi

# Check every selected batch before running the first one, so an overwrite
# conflict never leaves a partial multi-batch result.
for name in "${selected[@]}"; do
  batch_dir="$out_dir/batch-$name"
  for artifact in mutations.xml pit.log run-meta.txt; do
    if [[ -e "$batch_dir/$artifact" && "$force" -eq 0 ]]; then
      die "refusing to overwrite $batch_dir/$artifact; pass --force to replace selected batch artifacts"
    fi
  done
done

mkdir -p "$out_dir"

run_batch() {
  local name="$1"
  local batch_dir="$out_dir/batch-$name"
  local classes command_text started finished rc xml_archived
  local command

  classes="$(batch_classes "$name")"
  command=(mvn -o -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage
    "-DtargetClasses=$classes"
    "-DtargetTests=$package.*"
    "-Dmutators=$mutators")
  command_text="$(printf '%q ' "${command[@]}")"
  command_text="${command_text% }"

  mkdir -p "$batch_dir"
  if [[ "$force" -eq 1 ]]; then
    rm -f "$batch_dir/mutations.xml" "$batch_dir/pit.log" "$batch_dir/run-meta.txt"
  fi

  started="$(utc_now)"
  {
    printf 'batch: %s\n' "$name"
    printf 'started: %s\n' "$started"
    printf 'repository: %s\n' "$repo_root"
    printf 'commit: %s\n' "$(git rev-parse HEAD)"
    printf 'working-tree-status:\n'
    git status --porcelain
    printf 'java-home: %s\n' "${JAVA_HOME:-<unset>}"
    printf 'path-java:\n'
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
      "$JAVA_HOME/bin/java" -version 2>&1
    else
      java -version 2>&1
    fi
    printf 'maven-build-jdk:\n'
    mvn -version 2>&1 | sed -n '1,4p'
    printf 'scope: root package only; queue subpackage excluded\n'
    printf 'mutators: %s\n' "$mutators"
    printf 'no-history-input: true\n'
    printf 'command: %s\n' "$command_text"
  } > "$batch_dir/run-meta.txt"

  # A failed compile or PIT startup must not archive the previous batch's report.
  rm -f target/pit-reports/mutations.xml

  set +e
  "${command[@]}" > "$batch_dir/pit.log" 2>&1
  rc=$?
  set -e

  finished="$(utc_now)"
  xml_archived=false
  if [[ -f target/pit-reports/mutations.xml ]]; then
    cp target/pit-reports/mutations.xml "$batch_dir/mutations.xml"
    xml_archived=true
  fi
  {
    printf 'finished: %s\n' "$finished"
    printf 'exit-status: %s\n' "$rc"
    printf 'mutations-xml-archived: %s\n' "$xml_archived"
  } >> "$batch_dir/run-meta.txt"

  if [[ "$xml_archived" != true ]]; then
    printf 'run-pit-core: batch %s produced no target/pit-reports/mutations.xml\n' "$name" >&2
    return 1
  fi
  if [[ "$rc" -ne 0 ]]; then
    printf 'run-pit-core: batch %s failed with exit %s; see %s/pit.log\n' "$name" "$rc" "$batch_dir" >&2
    return "$rc"
  fi

  printf 'batch %s archived under %s\n' "$name" "$batch_dir"
}

for name in "${selected[@]}"; do
  run_batch "$name"
done
