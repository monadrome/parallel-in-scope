You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 3e04f590902bed50af4ddb4d8e6347630c8f80ec to 2684bd143909d9b6bfa660202e7079a010d529bd. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-2684bd14-20261127-160259-deab5f/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-2684bd14-20261127-160259-deab5f/inputs/context.md. Do not modify the snapshot. Do not read
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

# Review context, round 2: remove thread-pool starvation observation

Range: 3e04f590 .. 2684bd14 (two follow-up commits on top of `feat!: remove thread-pool starvation
observation`, which removed `ParRuntime.openThreadPoolStarvationObservation(Duration)`,
`ThreadPoolStarvationObservationScope`, `ThreadPoolStarvationReport`, and
`ParRuntimeSnapshot.activeObservations()`).

Settled round-1 findings (confirmed and fixed in this range; do not re-raise unless the fix is wrong):

- `design/task-group-adopted-futures.md` header listed the removed observation.
- `README.md` / `README.zh-CN.md` capability claims listed starvation diagnostics.
- `BACKLOG.md` identity-probe entry cited the removed `NO_ISSUE` status.

Commits in range:

- `docs: drop stale starvation-observation references found in review` (the three fixes above).
- `test: pin timeout-action thread retirement at quiescence`: adds
  `ParRuntimeTest.quiescenceRetiresTheTimeoutActionThreads`, written after focused PIT showed that
  deleting `timeoutActionPool.shutdown()` in `ParRuntime.shutdownServicesWhenAdmissionsComplete`
  survived.

Read every changed file in the range. Attack surfaces: whether each documentation fix is accurate
against the code and the remaining contracts; whether the new test is deterministic, bounded, releases
what it creates, and actually detects the missing pool shutdown (and only that) through documented
behavior (`awaitQuiescence` and `releases()` Javadoc); Java 8/11 test-source compatibility; and any
stale reference to the removed API the first round missed anywhere in the repository's live documents.

Do not edit files.
