You are one independent reviewer in the llm-review-bench benchmark.

Review the change from e15010cf224e9ddbd2dfeca8262f6c1961eddee2 to 2cf9df189b146ca8568c3568a716580b63302ef1. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-2cf9df18-20261124-012349-829543/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-2cf9df18-20261124-012349-829543/inputs/context.md. Do not modify the snapshot. Do not read
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

# Final independent review: hidden assurance regressions

Review the complete range `e15010cf224e9ddbd2dfeca8262f6c1961eddee2..2cf9df189b146ca8568c3568a716580b63302ef1`.
Read the immutable diff supplied by the prompt and every changed file below in the head snapshot.
This is a fresh re-review after confirmed findings; read the settled findings, verify their fixes, and look beyond them.

## Changed paths

- `.agents/skills/assurance-report/SKILL.md`
- `.agents/skills/contract-diff/SKILL.md`
- `.agents/skills/contract-extract/SKILL.md`
- `.agents/skills/contract-first-assurance/SKILL.md`
- `.agents/skills/contract-first-assurance/adapters/vformation.yaml`
- `.agents/skills/contract-first-assurance/evals/evals.json`
- `.agents/skills/contract-first-assurance/package.yaml`
- `.agents/skills/contract-first-assurance/references/host-adapter.md`
- `.agents/skills/contract-first-assurance/references/reproducible-runs.md`
- `.agents/skills/contract-first-assurance/scripts/verify_run.py`
- `.agents/skills/isolated-test-generation/SKILL.md`
- `.github/workflows/ci.yml`
- `AGENTS.md`
- `pom.xml`
- `scripts/check-contract-tests.py`
- `scripts/check-verification-integrity.py`
- `scripts/tests/test_check_contract_tests.py`
- `scripts/tests/test_verify_run.py`
- `verification/contract-tests/AGENTS.md`
- `verification/contract-tests/pom.xml`
- `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0001TaskIdContractTest.java`

## Settled findings to verify

- C01 / `kiro:0`: lexical `@Test` matching accepted a class with no executable test. The fix must use per-class Surefire XML execution evidence and reject missing, skipped, failed, and errored testcases. CI and assurance promotion must run the check after a clean test run.
- C02 / `kiro:1`: a run could claim `promoted` without an archived copy. The verifier must enforce the exact CSV schema, allowed statuses and reasons, safe unique paths and IDs, existing archive copies, and SHA-256 artifact coverage.

Both findings were reproduced before the fix and reverse-verified by disabling each new check. Treat them as settled only if the final implementation and tests actually close them.

## Attack surfaces

- Profile activation and default-reactor exclusion; Maven dependency resolution, Java 8 production bytecode, Java 11 test compilation, and JUnit discovery.
- Concrete bypasses of metadata, per-class execution evidence, stale reports, malformed XML, and Surefire class-name matching.
- Assurance v2 finalization, required-gate handling, promotion eligibility, CSV parser edge cases, safe paths, duplicate identities, and SHA-256 coverage.
- Consistency between every workflow MUST/MUST NOT and the actual scripts, POMs, CI job, and agent instructions.
- Context isolation between implementation, isolated test generation, assurance promotion, and independent review.
- The C-0001 public contract and whether its assertions detect opposite behavior.
- The verifier test class rename and its sixteen exact verification-integrity exceptions: confirm every original test remains and no unrelated exception is introduced.
- Incoming verification-integrity behavior and protected-path reporting on the rebased development line.

## Required assurance classification

The earlier Kiro review rejected `assurance: SKIPPED`; this change now requires a full contract-first-assurance run. Do not reclassify it as skip-eligible.

The first Codex review timed out twice and a later Codex attempt hit `max_output_tokens`; none produced findings or a clean baseline. Refactoring review settled R1 as deferred, R2 as reverted for excess growth, R3 as rejected, and R4 as applied. Review the complete final range rather than relying on those results.
