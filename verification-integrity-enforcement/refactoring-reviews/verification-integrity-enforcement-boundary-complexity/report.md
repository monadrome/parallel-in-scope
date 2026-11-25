# Refactoring review: verification integrity boundaries

- Base / behavior head: 06efaae43c0f343efc6e2bb6c289b907b218b9be / 08b8df42
- Plan: sharded, 4 detectors, lenses M/D/S/T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | kimi | rerun-completed | 2 |
| D-1 | D | kimi | rerun-completed | 0 |
| S-1 | S | kimi | rerun-completed | 3 |
| T-1 | T | kimi | rerun-completed | 2 |

The initial codex attempt failed when the DeepSeek account returned 402 Insufficient Balance. The fresh Kimi retry completed all detectors with an unmodified snapshot.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M01 | comparison_form | T1 -> T1 | rejected | Moving comparison policy onto Source would add unnecessary API and mix representation with assertion semantics. |
| R2 | M07 | scan_python | T1 -> T1 | rejected | Hiding node-identity results inside name resolution would make control flow less explicit; the current scanner shows decorator and runtime decisions directly. |
| R3 | S02 | scan_typescript | T0 -> T0 | rejected | The same extraction was previously applied and reverted for exceeding the configured growth ratio. |
| R4 | S02 | self_comparing | T0 -> T0 | applied | 5180070f extracts the fresh-identity literal predicate without changing comparison policy. |
| R5 | S03 | python_pytest_imports/python_function_bindings | T0 -> T0 | applied | 5180070f centralizes pytest-module recognition in `is_pytest_module`. |
| R6 | T04 | TypeScriptIntegrityTest | T0 -> T0 | rejected | Moving assertions into helpers detaches them from callers and triggers the documented V4 attribution rule. |
| R7 | T04 | PythonIntegrityTest | T0 -> T0 | rejected | Same V4 caller-attribution problem; assertions remain explicit in each test. |

## Verification

- Spotless: skipped; no Java source changed in this refactor.
- Targeted integrity and baseline tests: pass, 61 tests.
- Full `mvn test` on JDK 25: pass on the final retry; one earlier Lincheck attempt reported `Concurrent test has hung` during extreme environment slowdown.
- Full script discovery: pass, 477 tests on the final retry; earlier attempts timed out or were stopped during extreme environment slowdown.
- Verification integrity against `origin/dev/v0.5.0`: pass; three protected changes reported.
- `check-race-claims.py`: skipped; no concurrency item.
- `refactoring-review.py verify`: pass; no oracle edits or non-refactor commits, growth ratio 0.062 below the 0.3 limit.
- PIT: skipped under `verification/pit.md`; only Python verification tooling and its contract changed, with no Java production or Java mutation evidence changes.

## Proposed checks

None; no finding was mechanizable.

## Handoff to code review

- Review range: 06efaae43c0f343efc6e2bb6c289b907b218b9be..5180070f
- Refactor commits: 5180070f, 47f6d18f, ece0c43e, e4814ef9
- Attack surface: identity suffix/member behavior and pytest module/shadowing behavior after predicate extraction.
- Settled findings code review should not re-raise: R1-R7.
