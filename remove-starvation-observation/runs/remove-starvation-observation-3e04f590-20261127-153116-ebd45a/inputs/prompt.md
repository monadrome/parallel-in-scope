You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 5d79d305e1cfc81d8bcd9be8981ec9b82f460002 to 3e04f590902bed50af4ddb4d8e6347630c8f80ec. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-3e04f590-20261127-153116-ebd45a/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-3e04f590-20261127-153116-ebd45a/inputs/context.md. Do not modify the snapshot. Do not read
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

# Review context: remove thread-pool starvation observation

Range: 5d79d305 (origin/dev/v0.5.0) .. 3e04f590, one commit `feat!: remove thread-pool starvation observation`.

The maintainer decided to delete the public thread-pool starvation observation feature
(`ParRuntime.openThreadPoolStarvationObservation(Duration)`, `ThreadPoolStarvationObservationScope`,
`ThreadPoolStarvationReport`, `ParRuntimeSnapshot.activeObservations()`). The feature shipped in 0.4.0;
the project is `0.x` and allows breaking changes with a changelog migration entry. The removal decision
itself is settled; review whether the removal is complete, correct, and consistent.

Read every changed file in the diff, including documentation, scripts, ADR, skill reference, and tests.

Attack surfaces:

- Interleavings: `ParRuntime` service shutdown (`shutdownServicesWhenAdmissionsComplete`), admission
  (`whileOpen`/`enterAdmission`), `close()`, `awaitQuiescence(Duration)`, `inFlight()`, and `snapshot()`
  after the observation counter and sampling scheduler are removed. Check that no remaining path still
  depends on the removed counter or scheduler and that shutdown and quiescence remain correct.
- Contract versus implementation: diff each MUST/MUST NOT named in the commit message (the deleted
  `design/thread-pool-starvation-observation.md`, `design/task-group-adopted-futures.md` A7, and the
  `ParRuntime` Javadoc for `close()`, `closed()`, `awaitQuiescence()`, `inFlight()`, `snapshot()`)
  against the code, and look for live documents (user guides in `docs/en` and `docs/zh`, design
  contracts, ADRs, decision log, idea graveyard, changelog, README, demo, integrations,
  `verification/maven-central-consumer`) that still describe the removed API or its lifecycle effects.
- Test quality: the deleted test class, the rewritten
  `TaskGroupAdoptedFutureTest.aSourceQueuedBehindItsOwnWaiterRecordsAWaitCycleAndEndsAtTheDeadline`, the
  reduced `ParRuntimeSnapshotTest` cases, `PublicApiSurfaceTest`, and the new
  `REVIEWED_EXCEPTIONS` entries in `scripts/check-verification-integrity.py`. Check whether any
  remaining behavior lost its only evidence.
- Java 8 and generics: core targets Java 8 bytecode; check imports and any API use left behind.
- Changelog: the breaking-change entry must be self-contained with a migration action.

Do not edit files.
