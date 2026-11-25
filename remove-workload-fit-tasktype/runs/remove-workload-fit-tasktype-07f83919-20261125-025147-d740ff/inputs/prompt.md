You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 06efaae43c0f343efc6e2bb6c289b907b218b9be to 07f83919e72414a6987648c3288788a5db7abc6b. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-07f83919-20261125-025147-d740ff/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-07f83919-20261125-025147-d740ff/inputs/context.md. Do not modify the snapshot. Do not read
benchmark gold labels, adjudication records, or other reviewers' output.

Return exactly one JSON object and nothing else (no Markdown fences, no prose):

{
  "summary": "one short paragraph describing the review",
  "findings": [
    {
      "file": "repository-relative/path.ext",
      "lineStart": 123,
      "lineEnd": 130,
      "title": "short defect title",
      "trigger": "concrete input, interleaving, or condition that reaches the defect",
      "consequence": "observable failure or contract violation",
      "evidence": "code path with file:line references",
      "severity": "P0|P1|P2|P3",
      "confidence": 0.0,
      "suggestedFix": "actionable fix"
    }
  ]
}

Rules:
- Report only defects introduced or materially exposed by this change.
- Every finding must have a concrete trigger and a user-observable consequence.
- Cite repository-relative files and the line range in the head snapshot.
- confidence is a number from 0 to 1.
- At most 20 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Remove TaskType and workload-fit screening: full adversarial review

Review the complete `06efaae43c0f343efc6e2bb6c289b907b218b9be..07f83919e72414a6987648c3288788a5db7abc6b`
diff and read every changed file read-only. The implementer is Claude (Opus). Do not edit files, run
builds, or commit. Report only defects you can tie to a file and symbol with a concrete failure
scenario.

## What the change does

ADR 0016 (`adr/0016-remove-tasktype-and-workload-fit-screening.md`) removes the workload declaration
and both of its consumers, with no deprecation step:

- Public API removed: `TaskType`; `BatchOptions.taskType`/`TaskOptions.taskType` withers and accessors;
  `ParRuntime.Builder.register(ParId, ExecutorService, TaskType)`; `Par.taskType()`;
  `ParRuntime.workloadFitReport()`; `WorkloadFitReport` with its nested `ParMeasurement` and
  `UnitMeasurement`.
- Internals removed: the submission-time mismatch comparison (`TaskTypeMismatches`), the low-CPU
  screen (`WorkloadFit`, `WorkloadClock`, `SystemWorkloadClock`, the `ThreadMXBean` reads), the
  timing hook in `ScopedCallable`, the `Par` held by `MultiTaskContext`, and the type carried by
  `UnitSpec` and `TaskNode`.
- Kept unchanged: `rejectEnqueue` and `SubmissionAwareBlockingQueue` refusal, the once-per-`Par`
  inert-refusal warning in `Par.diagnoseAdmission`, announcement delivery on a runtime-owned thread,
  the executor wait graph and its cycle warning, starvation observation, and `ParRuntimeSnapshot`.
- Docs: ADR 0016 supersedes ADR 0008 and ADR 0010 and partially supersedes ADR 0001 and ADR 0005;
  `design/workload-fit-screening.md` is retired; `design/task-group-api-and-options.md` section 3.2.1
  is rewritten as "Admission diagnostics"; user guides, diagnostics reference, READMEs, graveyards,
  decision log, demo articles, and the CHANGELOG migration entry are updated.

## Contracts to diff line by line against the implementation

- `design/task-group-api-and-options.md` section 3.2.1 Admission diagnostics: refusal is expressed
  only by `rejectEnqueue`; every unit (batch, single task, group member, group combine) is checked at
  admission; the first refusing unit through a `Par` whose executor lacks a
  `SubmissionAwareBlockingQueue` logs one warning; a `Par.mapAndCombine` terminal combine does not
  request refusal; diagnostics only log or enter reports and never fail a task, batch, or group or
  replace its result; logs are delivered on a runtime-owned thread and the admission thread never
  invokes log handlers; records are independent and may be out of order.
- The queue-type paragraph of the same document: both queue types keep their enqueue,
  dynamic-capacity, and concurrency semantics.
- `design/task-group-adopted-futures.md` A7: adopted members undergo no admission diagnostics.
- ADR 0016 decisions 1-4, ADR 0005 rule 10, ADR 0001's L1 row.

## Attack surfaces

1. Interleavings: the warning's compare-and-set and announcement path after the screen's removal;
   `ScopedCallable.call` now returns the delegate's result directly, so check exception, interrupt,
   and cleanup paths around the delegate and any execution-exit or release ordering that used to
   sit next to the removed timing.
2. Contract versus implementation: every kept MUST/MUST NOT in section 3.2.1 against
   `Par.diagnoseAdmission`, its callers in `Par` and `TaskGroup`, and
   `SubmissionAwareBlockingQueue.offer`; no removed MUST survives in a live document.
3. Test quality: `scripts/check-verification-integrity.py` now lists every deleted test file,
   deleted or renamed case, and reduced assertion count in `REVIEWED_EXCEPTIONS`. Report an entry
   only if the removed test or assertion still guarded behavior that exists after this change (for
   example default enqueue, `rejectEnqueue` refusal, the inert-warning combine path, or the task
   boundary), and say which live behavior lost its evidence. Check that renamed tests still test what
   their names claim.
4. Java 8 and generics: the public surface, `verification/maven-central-consumer`, and the demo still
   compile for `--release 8`; no overload resolution changed for remaining `register` and option
   calls.
5. Documentation: CHANGELOG migration accuracy, ADR supersession links in both directions, the
   decision-log entry, and stale references to removed API in live documents. Frozen migration
   guides (`docs/*/migration-v0.*.md`), dated `verification/` records, and released CHANGELOG
   sections are intentionally unchanged.

## Settled; do not re-report

- Refactoring-review (6 kimi detectors) produced no findings and no refactor commits.
- `integrations/src/main/java/integrations/app/QuoteApplication.java` already failed to compile
  against the current snapshot before this change (`outcomeCounts()` and `fold` from an earlier
  change); this diff does not touch it. Report it only if this diff makes it worse.
- Deleting obsolete verification for removed API is intentional; the maintainer decided the removal.

## Verification already run on the committed tree (JDK 25)

`mvn -Pcontract-tests clean install` (3395 core tests, 2 Lincheck, hidden contract suite) passed;
demo `spotless:check package` (54 tests) and its Java 8 bytecode check passed; the Maven Central
consumer (11 tests) passed; integrations sources other than `QuoteApplication` compile; the
documentation, ADR, proposal, design-route, English-docs, Java-conventions, adopt-compile,
worktree-skill, baseline, and verification-integrity checks passed; the script test suite (460
tests) passed.
