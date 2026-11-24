# Refactoring review: contract assurance gate retrospective

- Base / behavior head: 8e6d3f3e7b374fd1cbb1840026377c45d2b012c3 / 7cb93aa6e22e7611ca3344d407cf8278069f40f2
- Plan: per-lens, 1 detector, lenses T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates; no core Java candidates were in scope.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| T | T | codex | completed | 4 raw, 3 merged |

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | T04 | `VerifyRunAcceptedExceptionTest` manifest helpers | T0 -> T0 | reverted | Applied on the current development line and replayed at the behavior head; verification measured growthRatio 0.9053 above the 0.3 limit, so the refactor was withdrawn. |
| R2 | T05 | `VerifyRunAcceptedExceptionTest` | T0 -> T0 | rejected | Already landed independently as `VerifyRunTest` in the later reviewed test rename; applying it again would be a no-op. |
| R3 | T04 | `VerifyRunAcceptedExceptionTest` passing-run and assertion helpers | T0 -> T0 | reverted | Applied with R1 and withdrawn by the same growth stop rule; tests remained green and exact error membership assertions were preserved. |

## Verification

- Targeted tests: pass; 16 tests passed on the replayed original scope and 34 on the current development scope.
- Full Python suite: pass; 347 tests passed both before the refactor was reverted and on the final reverted tree.
- Spotless: skipped; Python test-only refactor, no Java sources changed.
- Full `mvn test`: pass on the final reverted tree with JDK 25; 3446 unit tests and 2 Lincheck tests passed. The first attempt used the wrong default JDK and was rerun with the required version.
- `check-race-claims.py`: skipped; no T1 concurrency items.
- `refactoring-review.py verify`: fail and disposition completed; the retrospective run first included unrelated later commits, then a clean replay at the behavior head found no non-refactor commits but growthRatio 0.9053 exceeded 0.3. R1/R3 were reverted as required. Oracle edits were assertion-preserving helper extraction, not weakened oracles.
- PIT: skipped; the original change modified Python assurance tooling and workflow documents, with no affected core Java production classes or mutation sites.

## Proposed checks

None; all merged findings have `mechanizable: false`.

## Handoff to code review

- Original behavior review range: 8e6d3f3e7b374fd1cbb1840026377c45d2b012c3..7cb93aa6e22e7611ca3344d407cf8278069f40f2
- Replayed refactor for verification only: 505e90100c9943779a43cf95b118d6386a3b2d6f
- Current development refactor and withdrawal: e2039f533ae4a9db52761ac7e436bb01d72cb16b and its revert
- Attack surface: behavior preservation of R1/R3 was checked by targeted and full Python tests; no refactor remains in the final tree.
- Settled findings code review should not re-raise: R1, R2, R3.
