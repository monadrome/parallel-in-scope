You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 0e804a8b08a8c5bfcce12e9d831dc44b197b557e to e15010cf224e9ddbd2dfeca8262f6c1961eddee2. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-enforcement/.review-benchmark/runs/verification-integrity-enforcement-e15010cf-20261124-025844-a5caf6/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-enforcement/.review-benchmark/runs/verification-integrity-enforcement-e15010cf-20261124-025844-a5caf6/inputs/context.md. Do not modify the snapshot. Do not read
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
- At most 5 findings. Return {"summary":"...","findings":[]} when you find
  nothing; do not return an empty response or prose.
- Do not include shell commands, patches, or hidden reasoning outside the JSON object.

## Shared context (verbatim, immutable)

# Contract-first assurance skip confirmation

Read the complete 0e804a8b08a8c5bfcce12e9d831dc44b197b557e..e15010cf224e9ddbd2dfeca8262f6c1961eddee2 diff and every changed file read-only. Also read `.agents/skills/project-delivery/references/assurance.md` at the head and apply its Run required and Skip eligible conditions to the complete semantic diff.

This review's primary purpose is to decide whether the original verification-integrity-gate delivery is eligible for `assurance: SKIPPED`. The changed scope is the repository's Java/Python/TypeScript verification-integrity checker and tests, CI/CODEOWNERS wiring, project command routing, gate contract, and delivery ledger. Confirm that this scope changes neither library runtime contracts or observable behavior nor behavioral evidence for those contracts. Do not treat the number of files or lines as the reason for skip eligibility.

If every Run required condition is absent, return zero findings and make the summary explicitly state: `assurance: SKIPPED confirmed against the complete diff`, followed by the changed scope and semantic reason. If any Run required condition holds or cannot be established, return a concrete finding identifying the condition and evidence instead of confirming the skip.

Also report any new blocking defect you encounter in the complete diff, but do not repeat previously settled findings unless still defective. Do not run commands or tests, modify files, or inspect benchmark judgments or gold.
