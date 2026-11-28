You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 2684bd143909d9b6bfa660202e7079a010d529bd to 348753998bc8c954817e5b40556daecd7eb89fa9. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-34875399-20261127-162241-9acb94/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.review-benchmark/runs/remove-starvation-observation-34875399-20261127-162241-9acb94/inputs/context.md. Do not modify the snapshot. Do not read
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

# Review context, lightweight final pass: remove thread-pool starvation observation

Range: 2684bd14 .. 34875399, three commits after round 2 of the adversarial review:

- `test: promote starvation-removal lifecycle regressions`: three hidden contract tests
  (C-0005, C-0006, C-0007) under `verification/contract-tests/`, adapted by a fresh context from sealed
  assurance candidates. You may read the hidden suite. The sealed source candidates are
  `/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.contract-first-assurance/2026-11-27-remove-starvation-observation/candidates/ContractTdd004*.java`,
  `ContractTdd008*.java`, and `ContractTdd010*.java`; the adaptation diffs are in the sibling
  `adaptation/` directory. Check that each promoted class keeps its candidate's preconditions,
  operation sequence, observation window, and every assertion; that it follows
  `verification/contract-tests/AGENTS.md` (class name, `@Tag`, unique IDs); and that it is bounded and
  deterministic.
- `docs: archive starvation-observation removal PIT evidence`: check every count and claim in
  `verification/pit-audit/2026-11-27-remove-starvation-observation.md` against the archived XML in its
  directory, and that the dispositions follow `verification/pit.md`.
- `docs: note the removed observation in ADR 0016's reconsideration criterion`: check the note
  against `adr/AGENTS.md` condensation rules.

Do not edit files.
