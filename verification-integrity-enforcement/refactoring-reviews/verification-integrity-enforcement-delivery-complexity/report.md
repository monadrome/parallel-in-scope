# Refactoring review: verification integrity delivery

- Base / behavior head: 06efaae43c0f343efc6e2bb6c289b907b218b9be / f869f40c
- Plan: per-lens, 4 detectors, lenses M/D/S/T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex | completed | 0 |
| D | D | codex | completed | 0 |
| S | S | codex | completed | 2 |
| T | T | codex | completed | 3 |

All detectors completed in the first attempt with an unmodified snapshot. A preceding detector run was superseded before merge by the function-local pytest import behavior fix.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | S02 | scan_typescript | T0 -> T0 | rejected | This extraction was already applied and reverted for exceeding the configured growth ratio; no new structural reason justifies repeating it. |
| R2 | S03 | mask_c_like | T0 -> T0 | applied | 47f6d18f pairs identical code/comment appends through one local helper, preserving both views byte for byte. |
| R3 | T04 | TypeScriptIntegrityTest pass cases | T0 -> T0 | rejected | Shared assertion helpers detach assertions from callers and trigger the gate's documented V4 attribution rule. |
| R4 | T04 | TypeScriptIntegrityTest fail cases | T0 -> T0 | rejected | Same V4 caller-attribution problem; explicit assertions remain in each test. |
| R5 | T04 | PythonIntegrityTest runtime skips | T0 -> T0 | rejected | Same V4 caller-attribution problem, including the deliberate per-test V4 negative assertions. |

## Verification

- Spotless: skipped; no Java source changed in this refactor.
- Targeted integrity and baseline tests: pass, 58 tests.
- Full `mvn test` on JDK 25: pass.
- Full script discovery: pass, 474 tests.
- Verification integrity against `origin/dev/v0.5.0`: pass; three protected changes reported.
- `check-race-claims.py`: skipped; no concurrency item.
- `refactoring-review.py verify`: pass; no oracle edits or non-refactor commits, growth ratio 0.0302 below the 0.3 limit.
- PIT: skipped under `verification/pit.md`; only Python verification tooling and its contract changed, with no Java production or Java mutation evidence changes.

## Proposed checks

None; no finding was mechanizable.

## Handoff to code review

- Review range: 06efaae43c0f343efc6e2bb6c289b907b218b9be..47f6d18f
- Refactor commits: 47f6d18f, ece0c43e, e4814ef9
- Attack surface: byte-identical masked views and offsets after pairing append operations.
- Settled findings code review should not re-raise: R1-R5.
