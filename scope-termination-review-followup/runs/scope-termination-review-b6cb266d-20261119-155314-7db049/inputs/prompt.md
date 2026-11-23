You are one independent reviewer in the llm-review-bench benchmark.

Review the change from b4fb20ec673d5a55f2bd8f324ed793ff4efc3372 to b6cb266d120c78ee934b7341fb757a1fbe62a049. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/scope-termination-review/.review-benchmark/runs/scope-termination-review-b6cb266d-20261119-155314-7db049/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/scope-termination-review/.review-benchmark/runs/scope-termination-review-b6cb266d-20261119-155314-7db049/inputs/context.md. Do not modify the snapshot. Do not read
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

Review the commit range origin/dev/v0.5.0..b6cb266d in the immutable snapshot. This is an internal behavior-preserving refactor following an already completed six-lens review; do not propose reopening the deleted ScopeTerminationCause design.

Read every changed file from the commit diff, including verification/race-claims/claims.json and all test helpers/tests. Attack surfaces:
- ScopeTermination.primaryOr must preserve primary-over-fallback precedence in both TaskBatch.freezeResults and TaskGroup.snapshot. Its eager fallback argument is intentional: fromResults is pure over an already frozen list; assess any actual observable or complexity regression rather than assuming laziness is required.
- Batch caller-cancellation override must still replace only a null or CANCELLED primary, never FAILED, and accumulator CAS/provisional fail-fast replacement must remain unchanged.
- Verify.verifyNotNull replaces internal invariant checks only; source remains caller-validated and the unreachable private-constructor kind check is removed.
- TaskGroup.explicitScopeTermination Javadoc must match both branches and the null fallback.
- TerminationAssertions overload must preserve every prior assertion at exactly the three intended call sites without widening TaskTerminationTest's oracle.
- The new race claim must mutate the intended source exactly once and its cited test must detect the intended semantics.
- Check Java 8 compatibility, nullability, generics, contract drift, and test quality.

Remain read-only. Return concrete defects with file and symbol evidence; do not edit files or run write operations.
