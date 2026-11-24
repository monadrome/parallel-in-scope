# Refactoring review: assurance verifier evidence binding

- Base / behavior head: 6981df77ec413177b9c5d076cf82eb1388036a2a / ff9410715e0a2788aaa43484ef91f4944a7969ba
- Plan: per-lens, 1 detector, lenses T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates; no core Java candidates were in scope.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| T | T | codex | completed | 3 raw, 3 merged |

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | T04 | `VerifyRunTest` manifest helpers | T0 -> T0 | deferred | The same helper extraction was attempted in the retrospective run and withdrawn because growthRatio 0.9053 exceeded the 0.3 stop rule. Reapplying it to a larger test class would repeat that failed tradeoff; retained as an open item here rather than growing this behavior fix. |
| R2 | T04 | `VerifyRunTest#mutation_policy` | T0 -> T0 | deferred | Sharing the five evidence names would add indirection between each required category and its concrete evidence files. Reconsider with the next evidence-schema change so the contract and fixture can be redesigned together; retained as an open item here. |
| R3 | T05 | `VerifyRunTest#test_rejects_placeholder_accepted_exception_evidence` | T0 -> T0 | deferred | The current name preserves the regression's original placeholder-evidence scenario, while the assertion distinguishes missing required evidence. Renaming would also require verification-integrity REVIEWED_EXCEPTIONS maintenance without changing behavior; retained as an open item here. |

## Verification

- Spotless: skipped; Python and documentation changes only, no Java sources changed.
- Targeted tests: pass; the five new regression tests failed before the fix and pass afterward.
- Full Python suite: pass; 353 tests passed with `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests`.
- Reverse verification: pass; temporarily restoring the pre-fix verifier produced eight expected failures, including the new boundaries and old-schema compatibility checks, then the fixed verifier was restored.
- Full `mvn test`: pass on the pre-fix final tree with JDK 25; 3446 unit tests and 2 Lincheck tests passed. No Java source changed in this fix.
- `check-race-claims.py`: skipped; no T1 concurrency items.
- `refactoring-review.py verify`: pass; no refactor commits, oracle edits, or non-refactor commits; refactorCodeLines 0 and growthRatio 0.0.
- PIT: skipped; Python assurance tooling and workflow documents only, with no affected core Java production classes or mutation sites.

## Proposed checks

None; all merged findings have `mechanizable: false`.

## Handoff to code review

- Review range: 6981df77ec413177b9c5d076cf82eb1388036a2a..ff9410715e0a2788aaa43484ef91f4944a7969ba; refactor commits: none
- Attack surface: accepted-exception evidence binding, frozen input and commit identity validation, unique non-empty gate names, and completed-manifest schema examples.
- Settled findings code review should not re-raise: R1, R2, R3.
