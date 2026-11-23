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
