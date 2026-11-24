You are one independent reviewer in the llm-review-bench benchmark.

Review the change from 0e804a8b08a8c5bfcce12e9d831dc44b197b557e to a6214b44766c8b502d192c4621f692258216d47a. The current working directory is a
read-only git worktree snapshot at the head commit. The unified diff is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-a6214b44-20261123-152612-6444e8/inputs/diff.patch;
the shared context is at /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/hidden-contract-regression/.review-benchmark/runs/hidden-contract-regression-a6214b44-20261123-152612-6444e8/inputs/context.md. Do not modify the snapshot. Do not read
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

Review the complete range `0e804a8b08a8c5bfcce12e9d831dc44b197b557e..a6214b44766c8b502d192c4621f692258216d47a`.
Read the immutable diff supplied by the prompt and every changed file below in the head snapshot.

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
- `scripts/tests/test_check_contract_tests.py`
- `scripts/tests/test_verify_run.py`
- `verification/contract-tests/AGENTS.md`
- `verification/contract-tests/pom.xml`
- `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0001TaskIdContractTest.java`

## Attack surfaces

- Profile activation and default-reactor exclusion; Maven dependency resolution, Java 8 production bytecode, Java 11 test compilation, and JUnit discovery.
- Concrete triggers that let missing/mismatched IDs or empty test classes silently pass; duplicate IDs across files and Surefire file-name discovery.
- Assurance v2 finalization, required-gate handling, promotion eligibility, and SHA-256 coverage of every promoted copy.
- Consistency between every added workflow MUST/MUST NOT and the actual scripts, POMs, CI job, and agent instructions.
- Context isolation between implementation, isolated test generation, assurance promotion, and independent review.
- The C-0001 public contract and whether its assertions can detect the opposite behavior.
- R4 renamed only the Python verifier test class; confirm no discovery or assertion regression.

## Assurance classification requiring independent confirmation

The initial commit records `assurance: SKIPPED` because production behavior and existing behavioral
evidence are unchanged, and the workflow/test additions do not weaken, remove, or replace existing
evidence. Confirm or reject this against `.agents/skills/project-delivery/references/assurance.md`
and the complete diff. A question or rejection upgrades the run to required.

The earlier review of a pre-rebase snapshot timed out twice and produced no findings; it supplies no
clean baseline. Refactoring review settled R1 as deferred, R2 as reverted for excess growth, R3 as
rejected, and R4 as applied. Do not treat a timed-out review as evidence.
