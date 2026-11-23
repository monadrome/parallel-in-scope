You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 0e804a8b08a8c5bfcce12e9d831dc44b197b557e to 287411045a115d27fb086e18dfd4c095e45518dd. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-gate/.review-benchmark/runs/verification-integrity-gate-28741104-20261123-151059-32c2f1/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-gate/.review-benchmark/runs/verification-integrity-gate-28741104-20261123-151059-32c2f1/inputs/context.md. Do not modify the snapshot. Do not read
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
- At most 3 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Independent verification-integrity review

Read the full origin/dev/v0.5.0..HEAD diff and all 9 changed files. Review the
current implementation, tests, CI, CODEOWNERS, and contract read-only. Do not run
commands, tests, or exploratory probes; read only the files needed to validate a
concrete concern, then return the required JSON immediately.

Check for new blocking defects in test deletion/suppression/assertion detection,
Java/Python/TypeScript parsing, baseline and exact-move handling, CODEOWNERS
last-match owner coverage, CI base selection, and refactor behavior preservation.
The implementation already has regressions for the suppression forms named in the
contract and for CODEOWNERS ownerless/last-match overrides and the 3 MB limit.
Do not re-report documented heuristic limitations or optional refactors.

Return at most 3 findings with a concrete trigger and observable consequence.
If none is supported by the changed code, return an empty findings list promptly.
