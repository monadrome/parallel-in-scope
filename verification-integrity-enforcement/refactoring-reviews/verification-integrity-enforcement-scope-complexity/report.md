# Refactoring review: verification integrity scope fixes

- Base / behavior head: 06efaae43c0f343efc6e2bb6c289b907b218b9be / 0d0c686b
- Plan: sharded, 4 detectors, lenses M/D/S/T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | kimi | completed | 2 |
| D-1 | D | kimi | completed | 1 |
| S-1 | S | kimi | completed | 3 |
| T-1 | T | kimi | completed | 2 |

All detectors completed in the first Kimi attempt with an unmodified snapshot. An earlier codex attempt was abandoned after DeepSeek returned 402 before producing findings.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M01 | Source comparison helpers | T1 -> T1 | rejected | Moving assertion policy onto Source adds unnecessary API and mixes representation with V5 semantics. |
| R2 | M05 | Source literal spans | T2 -> T2 | deferred | Open item only; sharing maskers risks changing deliberate Java/TypeScript literal differences and protected-path coverage. |
| R3 | D02 | scan_python decorator shadow check | T1 -> T1 | applied | 9ba18c80 removes the duplicate target-id disjunct; decorator and func are visited under the same scope. |
| R4 | S02 | scan_typescript | T0 -> T0 | rejected | The same extraction was previously applied and reverted for exceeding the configured growth ratio. |
| R5 | S03 | mask_c_like | T0 -> T0 | applied | 9ba18c80 centralizes literal span registration and synchronized masked/raw appends. |
| R6 | S03 | typescript_fresh_identity_literal | T0 -> T0 | applied | 9ba18c80 gives the shared suffix acceptance predicate one name. |
| R7 | T04 | PythonIntegrityTest | T0 -> T0 | rejected | Moving assertions into helpers detaches them from callers and triggers the documented V4 attribution rule. |
| R8 | T04 | TypeScriptIntegrityTest | T0 -> T0 | rejected | Same V4 caller-attribution problem; assertions remain explicit in each test. |

## Verification

- Spotless: skipped; no Java source changed in this refactor.
- Targeted integrity and baseline tests: pass, 63 tests.
- Full `mvn test` on JDK 25: pending final rerun.
- Full script discovery: pending final rerun.
- Verification integrity against `origin/dev/v0.5.0`: pass; three protected changes reported.
- `check-race-claims.py`: skipped; no concurrency item.
- `refactoring-review.py verify`: pass; no oracle edits or non-refactor commits, growth ratio 0.0404 below the 0.3 limit.
- PIT: skipped under `verification/pit.md`; only Python verification tooling and its contract changed, with no Java production or Java mutation evidence changes.

## Proposed checks

None; no finding was mechanizable.

## Handoff to code review

- Review range: 06efaae43c0f343efc6e2bb6c289b907b218b9be..9ba18c80
- Refactor commits: 9ba18c80, 5180070f, 47f6d18f, ece0c43e, e4814ef9
- Attack surface: byte-identical literal views, decorator scope, and identity suffix behavior after deduplication.
- Settled findings code review should not re-raise: R1-R8.
