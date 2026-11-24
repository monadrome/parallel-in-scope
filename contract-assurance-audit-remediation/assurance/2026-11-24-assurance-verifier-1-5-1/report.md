# Contract-first assurance: verifier 1.5.1

## Contracts and candidates

The completed frozen contract supplied the manifest schema and exact policy fields missing in round one. A fresh Kiro context generated seven candidates from only that contract and the sanitized API. All seven passed on the real implementation. They are duplicate coverage after behavior-level comparison with the persistent suite, so none was promoted. Reverse verification ran anyway: the base verifier produced 16 expected failures across the seven tests and their subcases. Candidate bytes remained sealed and the production source was untouched.

## Confirmed implementation defects

Independent review run `contract-assurance-audit-remediation-85451d8a-20261124-093740-b14d42` used kiro-cli 3.5.0, provider kiro, model gpt-5.6-terra, effort max. Both findings were adjudicated confirmed:

1. replacing `inputs/` with a symlink to the run root makes output artifacts satisfy the resolved containment check;
2. malformed `requiredEvidence` policies are not examined when no accepted exception uses them.

These defects block this frozen head. The subsequent fix and review are outside this run's immutable scope.

## Checks and remaining uncertainty

- Candidate behavior tests: PASS, 7 tests.
- Reverse verification: PASS, 16 expected failures on the old verifier; no temporary production mutation remained.
- Existing tests: PASS, 353 Python tests and 3446 + 2 Java tests on JDK 25.
- Documentation and integrity: PASS.
- Promotion: SKIPPED, all candidates duplicate existing behavioral evidence.
- Independent review: FAIL, two confirmed findings.
- Java consumer: NOT APPLICABLE to this Python-only tooling change.
- PIT: SKIPPED, no Java production class or mutation site; no raw status or accepted exception.
- Contract gaps: the prior input-package gap was closed and produced valid candidates. Remaining uncertainty is limited to the two confirmed review defects and behaviors outside the scoped contract.

Final gate: FAIL
