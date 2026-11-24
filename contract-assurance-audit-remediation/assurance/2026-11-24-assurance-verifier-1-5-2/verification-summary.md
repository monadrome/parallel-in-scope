# Verification summary

- Frozen package/adapter: 1.5.2 / vformation 1.5.2; base `6981df77ec413177b9c5d076cf82eb1388036a2a`, head/finalHead `4b20479a9dd5180b305a57cac8bea89d53cf5338`, clean.
- Isolated generation: PASS; fresh non-resumed codex-cli 0.169.0, provider magpie, model deepseek-v4-pro, with only the completed frozen contract, sanitized API, and instructions. Twenty-nine candidates were sealed before execution.
- Candidate tests: PASS; 29 tests passed on the real 1.5.2 verifier. Behavior-level comparison retained four new-coverage candidates: 008, 013, 015, and 021. The other 25 are duplicate coverage.
- Reverse verification: BLOCKED for promotion completeness. The base verifier produced 15 expected failures, including new-coverage candidates 013 and 021. It also passed new-coverage candidates 008 and 015, so those two lack a contrary-behavior failure. No production file was modified.
- Existing tests: PASS; 360 Python tests, plus 3446 Maven unit tests and 2 Lincheck tests on JDK 25.
- Documentation, worktree-skill, project-baseline, and verification-integrity checks: PASS.
- Promotion: BLOCKED; the frozen adapter resolves only the Java `verification/contract-tests` suite. No Python regression-suite root or exact metadata, test, and execution-check commands were resolved before this run. No candidate was promoted.
- Independent review: PASS; run `contract-assurance-audit-remediation-4b20479a-20261124-095413-6b429a` completed with codex-cli 0.169.0, provider magpie, model deepseek-v4-pro. Its one schema-version finding was rejected with confidence 0.85: rejecting legacy manifests without final-head and bound evidence is this remediation's intended behavior; no compatibility contract requires a v3 rename. The separate Kiro attempt failed on monthly quota and is not zero-finding evidence.
- Java consumer: NOT APPLICABLE; no Java API or consumer surface changed. PIT: SKIPPED under `verification/pit.md`; Python tooling only, with no Java production mutation site.
- Final outcome: BLOCKED. Candidate, existing-test, documentation, and independent-review checks passed, but two new candidates lack reverse failures and all four promotions lack frozen Python commands. A subsequent same-head run may resolve those explicit adapter and reverse-verification gaps; it cannot rewrite this result.
