# Refactoring review: assurance verifier promotion

- Base / behavior head: ffc53025 / 3434e3fd
- Plan: per-lens, 2 detectors, lenses M,T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates; no core Java candidates were in scope.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | kimi | completed | 1 raw, 1 merged |
| T | T | kimi | completed | 1 raw, 1 merged |

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M05 | four promoted `ContractVerifierTest` modules | T1 -> T1 | rejected | Combining the scenarios into one target violates the assurance promotion contract: every promoted candidate needs a unique target path and byte-identical frozen snapshot. The verifier rejects duplicate promoted target paths. Reuse of the existing fixture module already removes the substantial fixture duplication. |
| R2 | T04 | `ContractVerifierTest.setUp` | T1 -> T1 | deferred | The repeated two-line standard unittest composition is correct and keeps each unique promotion target self-contained. Extracting a new protected fixture helper would change and re-seal every promoted target without fixing a lifecycle defect. Open item for future test-suite maintenance if the fixture protocol becomes more complex; no refactor is required for this delivery. |

## Verification

- Targeted promoted tests: pass; exactly 4 tests, with no inherited repetition.
- Direct Python discovery: pass; 364 tests, exactly four more than the 360-test baseline.
- Canonical Python discovery: pass; 477 tests, exactly four more than the 473-test baseline.
- Promoted-target reverse verification: pass; real verifier 4/4 pass, contrary verifier fails exactly 008/015, base verifier fails exactly 013/021.
- Spotless: skipped; Python test changes only.
- Full `mvn test`: pass at the tree-identical implementation head with JDK 25; 3446 unit tests and 2 Lincheck tests. This increment changes no Java source.
- `check-race-claims.py`: skipped; no T1 concurrency items.
- `refactoring-review.py verify`: pass; no refactor commits or oracle edits.
- PIT: skipped; no Java production class or mutation site changed.

## Proposed checks

None; both merged findings have `mechanizable: false`.

## Handoff to code review

- Review range: ffc53025..3434e3fd; refactor commits: none
- Attack surface: unique promotion targets, fixture cleanup, direct/canonical discovery, and unchanged candidate assertions.
- Settled findings code review should not re-raise: R1, R2.
