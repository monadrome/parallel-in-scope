# Contract-first assurance: verifier 1.5.2

## Contracts and candidates

The frozen contract covers manifest identity, policy validity, evidence binding, frozen input containment, required gates, and reproducible reports. A fresh Codex context generated 29 candidates from only that contract and the sanitized API. All 29 passed on the real implementation. Behavior-level comparison marked 25 duplicate coverage and retained four new-coverage candidates: wrong policy field `008`, non-object unused policy `013`, absolute input path `015`, and nested input symlink `021`.

The base verifier produced 15 failures. This establishes reverse evidence for `013` and `021`, but not for `008` or `015`, which also passed on that verifier. Those two must not be described as successfully reverse-verified.

## Confirmed implementation defects

No unresolved implementation defect remains in this run's independent review. Run `contract-assurance-audit-remediation-4b20479a-20261124-095413-6b429a` used codex-cli 0.169.0, provider magpie, model deepseek-v4-pro. Its sole P3 finding requested schema v3 because legacy v2 manifests now receive field-level errors. It was rejected with confidence 0.85: rejecting the old false-success manifests is the intended change, no legacy-v2 compatibility requirement exists, and renaming the schema alone adds no behavioral assurance. The separate Kiro attempt at this head failed on monthly quota; it is failed evidence, not a clean review.

## Contract gaps and improvements

The earlier generation-input omissions were corrected: candidates received the baseline layout and exact policy fields. The remaining verification-adapter gap is the absence of a Python regression-suite root and exact promotion commands. A subsequent run must freeze those commands before promotion. It must also provide contrary-behavior failures for `008` and `015`, or leave their promotions blocked. Existing duplicate candidates should not be added merely to increase test count.

## Checks and remaining uncertainty

- Candidate behavior tests: PASS, 29 tests.
- Reverse verification: BLOCKED for complete new-coverage evidence; 15 base failures, including `013` and `021`, but no base failure for `008` or `015`.
- Existing tests: PASS, 360 Python tests and 3446 + 2 Java tests on JDK 25.
- Documentation, worktree-skill, project-baseline, and integrity checks: PASS.
- Promotion: BLOCKED, no resolved Python promotion suite or exact commands; no candidate promoted.
- Independent review: PASS after one finding was rejected with recorded rationale.
- Java consumer: NOT APPLICABLE to this Python-only tooling change.
- PIT: SKIPPED, no Java production class or mutation site; no raw status or accepted exception.
- Remaining uncertainty is limited to behavior outside the frozen contract and the explicitly blocked promotion evidence above.

Final gate: BLOCKED
