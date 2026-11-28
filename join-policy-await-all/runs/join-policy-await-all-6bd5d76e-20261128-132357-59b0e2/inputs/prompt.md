You are one independent reviewer in the llm-review-bench benchmark.

Review the change from ce7b5cc2b86d07ec32a861916ba029e9d9beba3a to 6bd5d76e6df59fa1cda7d69567e9c8439dbc18fc. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/join-policy-await-all/.review-benchmark/runs/join-policy-await-all-6bd5d76e-20261128-132357-59b0e2/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/join-policy-await-all/.review-benchmark/runs/join-policy-await-all-6bd5d76e-20261128-132357-59b0e2/inputs/context.md. Do not modify the snapshot. Do not read
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

# Review context, round 2: fixes after the first review of the join-policy change

Range: ce7b5cc2 .. HEAD, two commits on branch feat/join-policy-await-all:

1. `test: close the PIT gaps in the scope-termination and await-all paths` (f6c8797f): removes the
   unobservable token read from `TaskBatch#deriveScopeTermination`, makes the five-argument
   `TaskBatchResult` constructor require the derived termination, removes the synchronous
   `groupToken.failFastCancel()` on a group combine failure (the scope termination is now derived
   with the first failed task first), and adds tests for the direct-cancel priority, a running
   pool combine after all members succeeded, and the `joinPolicy` thread check.
2. `fix: address the first adversarial review of the join-policy change` (6bd5d76e): the
   `mapAndCombine` AWAIT_ALL combine gate becomes one `successfulAsList(views)` listener (no
   SEVERE aggregate logging), deadline tests move to `ManualClock`, and contract/user-doc text is
   corrected (direct-cancel attribution in groups, no `close()` in public docs, task-group
   cancellation and lifecycle contracts).

Settled in round 1 (do not re-raise unless the fix is wrong): FAIL_FAST group direct-cancel
attribution text, allAsList SEVERE logging under AWAIT_ALL, close() in public docs,
task-group-cancellation.md derivation text, lifecycle/adopt policy qualification, wall-clock
deadline tests.

Do not read `verification/contract-tests/`.

Attack surfaces: is removing the combine-failure `failFastCancel()` really unobservable (any
reader of the group token before or after convergence: values future, report, completion task,
member classification, parent propagation to nested scopes still running inside a member, the
FAIL_FAST bind callback order); does the new `successfulAsList(views)` gate preserve the
exactly-once combine submission and the deadline/cancelOn paths under AWAIT_ALL; is the batch
derivation without the token read equal for every reachable token state (TIMEOUT, CANCELLED,
PROPAGATED_CANCELLED, FAIL_FAST, caller cancel); test determinism; contract versus implementation
for every changed MUST.
