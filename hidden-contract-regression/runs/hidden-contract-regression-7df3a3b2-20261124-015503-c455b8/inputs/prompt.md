You are one independent reviewer in the llm-review-bench benchmark.

Review the change from e15010cf224e9ddbd2dfeca8262f6c1961eddee2 to 7df3a3b2a056235feb88f9a19bffcbad3fd37e0a. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-7df3a3b2-20261124-015503-c455b8/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-7df3a3b2-20261124-015503-c455b8/inputs/context.md. Do not modify the snapshot. Do not read
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

# Third independent review: hidden assurance regressions

Review the complete range `e15010cf224e9ddbd2dfeca8262f6c1961eddee2..7df3a3b2a056235feb88f9a19bffcbad3fd37e0a`.
Read the immutable diff supplied by the prompt and every changed file below in the head snapshot.
This is the third and final fresh review round. Verify all six settled findings and look for remaining blocking defects.

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

## Settled findings and required fixes

- C01: lexical `@Test` was not execution evidence. Require per-class Surefire XML and reject missing/skipped/failed/error outcomes after a clean run.
- C02: promoted rows need existing hashed archive copies, exact CSV schema, allowed statuses, reasons, safe unique IDs and paths.
- C03: same simple class names in another package must not satisfy execution evidence. Match the source package plus class exactly and reject empty method names.
- C04: every promoted row requires a passing `regression-tests` gate and successful exact-argv command records for `regression-metadata`, `regression-tests`, and `regression-execution`, frozen in `adapter.regressionSuite`.
- C05: reject non-canonical empty/dot/dot-dot path components so aliases cannot bypass uniqueness.
- C06: promoted candidate IDs must be non-empty, unique, and sealed in `candidate-status.csv`; source contract IDs must be non-empty.

All six were reproduced by failing tests before their fixes. Review the implementation and tests, not just the presence of documentation.

## Attack surfaces

- Profile activation and default-reactor exclusion; Maven dependency resolution, Java 8 production bytecode, Java 11 test compilation, and JUnit discovery.
- Metadata, fully qualified per-class execution evidence, clean reports, malformed XML, and Surefire matching.
- Assurance v2 finalization, conditional promotion gates, exact argv matching, command logs, CSV parser edge cases, canonical paths, sealed candidate origins, and SHA-256 coverage.
- Consistency between every workflow MUST/MUST NOT and the scripts, POMs, CI job, and agent instructions.
- Context isolation between implementation, isolated test generation, assurance promotion, and independent review.
- C-0001 public behavior and reverse verification.
- The verifier test class rename and its sixteen exact verification-integrity exceptions; no unrelated exception is allowed.
- Incoming verification-integrity behavior and protected-path reporting.

## Required assurance classification

The first Kiro review rejected `assurance: SKIPPED`; a full run remains required. The second Kiro review confirmed four additional defects, so its in-progress assurance artifacts are superseded by the final head and cannot finalize the gate.

The first Codex review timed out twice and a later Codex attempt hit `max_output_tokens`; none produced findings or a clean baseline. Refactoring review settled R1 as deferred, R2 as reverted for excess growth, R3 as rejected, and R4 as applied. Review the complete final range rather than relying on those results.
