You are one independent reviewer in the llm-review-bench benchmark.

Review the change from e57602a8288542b2d9090a37bf95188445da6df7 to f020503de120d5bcb525d568f90c02f6f8bcbe2c. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-f020503d-20261125-035711-ca9cee/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-workload-fit-tasktype/.review-benchmark/runs/remove-workload-fit-tasktype-f020503d-20261125-035711-ca9cee/inputs/context.md. Do not modify the snapshot. Do not read
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

# Remove TaskType and workload-fit screening: adversarial review round 3

Review the test commit `e57602a8288542b2d9090a37bf95188445da6df7..f020503de120d5bcb525d568f90c02f6f8bcbe2c`
read-only. The implementer is Claude (Opus). Do not edit files, run builds, or commit. Report only
defects you can tie to a file and symbol with a concrete failure scenario.

## Background

The delivery removes `TaskType`, the submission-time mismatch comparison, and the workload-fit
screen (ADR 0016). Rounds 1 and 2 reviewed the removal and its documentation fixes; their findings
are settled and must not be re-reported.

A PIT discovery run on the removal (all root-package tests, `VOID_METHOD_CALLS` included) left two
mutants `SURVIVED` in unchanged `TaskGroup` code: `TaskGroup$PreparationContext.prepareMember` and
`prepareTerminalCombine`, "removed call to `Par::diagnoseAdmission`". Their only killing test was the
deleted `TaskTypeMismatchWarningTest`. This commit adds
`InertEnqueueRefusalWarningTest.groupMembersCombinesAndSingleTasksAreCheckedAtAdmission` so that the
admission check of group members and group combines has evidence again. The implementer reports that
deleting either call fails the new test with "Expected size: 3 but was: 2".

## Contract to check the test against

`design/task-group-api-and-options.md` section 3.2.1 Admission diagnostics: refusal is expressed only
by `rejectEnqueue`; every unit (batch, single task, group member, group combine) is checked at
admission; the first refusing unit through a `Par` whose executor lacks a
`SubmissionAwareBlockingQueue` logs one warning per `Par`; a `Par.mapAndCombine` terminal combine does
not request refusal; diagnostics never fail a task or change its result; logs are delivered on a
runtime-owned thread. Tests replace that thread with `announcements(MoreExecutors.directExecutor())`.
`design/task-group-adopted-futures.md` A7: adopted members undergo no admission diagnostics.

Implementation: `Par.diagnoseAdmission`, `Par.admitUnit`, and the two `TaskGroup` call sites.

## What to check

1. Does the test kill exactly what it claims? Would it still pass if a member or combine were checked
   against the wrong `Par`, if the warning were issued for the group instead of the unit, or if the
   once-per-`Par` deduplication were shared across `Par` instances?
2. Determinism and isolation: warning delivery timing with the direct announcement executor, the
   shared `records` capture and the class-level `runtime`, registering one executor under three ids,
   closing the extra runtime, and the bounded `TaskFuture.get`.
3. Test conventions in `.agents/skills/project-delivery/references/conventions.md` and the
   verification-integrity rules in `verification/verification-integrity.md`.
4. Whether another live admission path (for example a nested batch inside a task, or a
   `mapAndCombine` combine with explicit options) still lacks evidence after this commit.

## Verification already run

`InertEnqueueRefusalWarningTest` and `TaskGroupTest` (47 tests) pass; spotless,
`check-verification-integrity.py --base f093bb12`, and `check-java-conventions.py` pass; reverse
verification as described above.
