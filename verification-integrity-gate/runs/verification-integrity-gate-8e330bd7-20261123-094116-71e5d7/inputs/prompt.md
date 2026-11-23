You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 20b2c58b9487a3c15284adca94d86954c3e4a67a to 8e330bd7dada615bd6aef713f581957eff526928. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-gate/.review-benchmark/runs/verification-integrity-gate-8e330bd7-20261123-094116-71e5d7/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/verification-integrity-gate/.review-benchmark/runs/verification-integrity-gate-8e330bd7-20261123-094116-71e5d7/inputs/context.md. Do not modify the snapshot. Do not read
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

# Independent adversarial review: verification integrity gate

Review the complete diff from origin/dev/v0.5.0 to HEAD and every changed file:
AGENTS.md, .agents/skills/project-delivery/references/commands.md,
.github/CODEOWNERS, .github/workflows/ci.yml, scripts/check-project-baseline.py,
scripts/check-verification-integrity.py,
scripts/tests/test_check_project_baseline.py,
scripts/tests/test_check_verification_integrity.py, and
verification/verification-integrity.md. Read the implementation and tests from the
snapshot; do not edit files.

Contract under review:
- MUST reject deleted test files and cases, new test suppression, reduced assertion
  counts, and new constant assertions relative to the pull-request base.
- MUST report protected verification infrastructure changes and retain
  repository-owner CODEOWNERS coverage.
- MUST NOT treat protected infrastructure edits as self-approving or allow
  exceptions to CODEOWNERS protection.

Attack surfaces to probe explicitly:
- test deletion, renaming, exact moves, duplicate names, helper methods, custom or
  multiline test annotations, comments and literals;
- assertion-count preservation attacks, constant/self-comparison false positives,
  baseline subtraction, file moves containing existing suppressions;
- Java's lightweight parser, Python AST behavior, and TypeScript masking/parsing;
- CODEOWNERS precedence/self-modification, exception handling, workflow self-removal,
  merge-base selection, untrusted fork input, and command/revision injection;
- whether CI and documentation actually enforce the stated contract without making
  legitimate maintenance impossible;
- test quality and missing reverse verification, plus unnecessary complexity in the
  new 656-line checker.

Report only concrete findings supported by file content and an executable or
source-level reproduction hypothesis. Distinguish blocking defects from documented
limitations and optional improvements.
