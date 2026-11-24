# Verification summary

- Frozen package/adapter: 1.5.1 / vformation 1.5.1; base `6981df77ec413177b9c5d076cf82eb1388036a2a`, head/finalHead `85451d8a2dc6efd9f60008ba8a2b1bcb8edbe235`, clean.
- Isolated generation: PASS; fresh non-resumed kiro-cli 3.5.0, gpt-5.6-terra, max effort, with only the completed frozen contract, sanitized API, and instructions. Seven candidates were sealed before execution.
- Candidate tests: PASS; 7 tests passed on the real 1.5.1 verifier. Behavior-level comparison marked all seven duplicate coverage because the persistent suite already tests the same preconditions, operations, observations, and assertions.
- Reverse verification: PASS; the same sealed candidates produced 16 expected failures on the base verifier, including subcases. The production worktree was not modified.
- Existing tests: PASS; 353 Python tests, plus 3446 Maven unit tests and 2 Lincheck tests on JDK 25.
- Documentation and integrity: PASS.
- Promotion: SKIPPED; no final new-coverage candidate. Header-only CSV and empty `promotions/`.
- Independent review: FAIL; run `contract-assurance-audit-remediation-85451d8a-20261124-093740-b14d42` completed with two findings, both confirmed: a symlinked inputs root bypass and malformed unused policies escaping validation.
- Java consumer: NOT APPLICABLE; no Java API or consumer surface changed. PIT: SKIPPED under `verification/pit.md`; Python tooling only, with no Java production mutation site.
- Final outcome: FAIL. Candidate and existing behavior checks passed, but confirmed independent-review defects require a new frozen head and run.
