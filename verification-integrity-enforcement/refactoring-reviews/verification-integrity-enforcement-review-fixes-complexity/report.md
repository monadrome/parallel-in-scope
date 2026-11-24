# Refactoring review: verification integrity review fixes

- Base / behavior head: 06efaae43c0f343efc6e2bb6c289b907b218b9be / 42586e6a
- Plan: per-lens, 4 detectors, lenses M/D/S/T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex | completed | 1 |
| D | D | codex | completed | 0 |
| S | S | codex | completed | 3 |
| T | T | codex | completed | 2 |

All detectors completed in the first attempt with an unmodified snapshot.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M07 | self_comparing | T1 -> T1 | rejected | A new Source query abstraction is not needed for two local uses; the existing mask contract and comparison helpers keep the invariant local without adding API. |
| R2 | S02 | scan_typescript | T0 -> T0 | rejected | The same extraction was already applied and reverted in this delivery for exceeding the configured growth ratio; no new reason justifies repeating it. |
| R3 | S01 | Source#comparable | T0 -> T0 | applied | ece0c43e renames the text view and offset map to `comparison_view` and `literal_spans`. |
| R4 | S01 | python_import_aliases | T0 -> T0 | applied | ece0c43e renames the two-result helper to `python_pytest_imports`. |
| R5 | T04 | TypeScriptIntegrityTest | T0 -> T0 | rejected | Moving assertions into shared helpers detaches them from test callers and triggers the gate's documented V4 attribution rule. |
| R6 | T04 | PythonIntegrityTest | T0 -> T0 | rejected | Same V4 caller-attribution problem; explicit per-test assertions remain necessary. |

## Verification

- Spotless: skipped; no Java source changed in this refactor.
- Targeted integrity and baseline tests: pass, 56 tests.
- Full `mvn test` on JDK 25: pass.
- Full script discovery: pass, 472 tests.
- Documentation references and local links: pass.
- Verification integrity against `origin/dev/v0.5.0`: pass; three protected changes reported.
- `check-race-claims.py`: skipped; no T1 concurrency item.
- `refactoring-review.py verify`: pass; no oracle edits or non-refactor commits, growth ratio 0.1656 below the 0.3 limit.
- PIT: skipped under `verification/pit.md`; only Python verification tooling and its contract changed, with no Java production or Java mutation evidence changes.

## Proposed checks

None; no finding was mechanizable.

## Handoff to code review

- Review range: 06efaae43c0f343efc6e2bb6c289b907b218b9be..ece0c43e
- Refactor commits: ece0c43e, with e4814ef9 from the preceding behavior round
- Attack surface: exact comparison behavior and wildcard suppression resolution after mechanical renames.
- Settled findings code review should not re-raise: R1-R6.
