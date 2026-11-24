# Refactoring review: verification integrity comparison semantics

- Base / behavior head: 06efaae43c0f343efc6e2bb6c289b907b218b9be / 5aef14d5da6344ae67461132a587fb14c5237cfe
- Plan: per-lens, 4 detectors, lenses M/D/S/T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates; the core Java scope had no candidates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | fresh-context detector | rerun-completed | 1 |
| D | D | fresh-context detector | rerun-completed | 0 |
| S | S | fresh-context detector | rerun-completed | 2 |
| T | T | fresh-context detector | rerun-completed | 1 |

The first detector attempt was discarded because concurrent test discovery modified its snapshot. The retry completed with `snapshotModified: false`.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M07 | normalize_comparison | T1 -> T1 | applied | `comparison_form` sorts literal spans in e4814ef9, making its traversal order explicit. |
| R2 | S02 | scan_typescript | T0 -> T0 | rejected | The same extraction was previously applied and reverted in this delivery because its growth ratio exceeded the configured limit; no new structural benefit justifies repeating it. |
| R3 | S06 | self_comparing | T0 -> T0 | applied | e4814ef9 compares operand forms once, then checks only the normalized left source span. |
| R4 | T04 | TypeScriptIntegrityTest | T0 -> T0 | rejected | Moving the assertions into a helper would detach them from their callers and trigger the gate's documented V4 attribution rule; the explicit tests remain clearer. |

## Verification

- Spotless: skipped; no Java source changed in this refactor.
- Targeted integrity and baseline tests: pass, 54 tests.
- Full `mvn test` on JDK 25: pass.
- Full script discovery: pass, 470 tests on the final retry. Two earlier attempts failed in unrelated process-cleanup tests because a child PID file was absent or empty; a focused retry passed and no production or test source for those checks changed.
- Documentation references and local links: pass.
- Verification integrity against `origin/dev/v0.5.0`: pass; three protected changes reported.
- `check-race-claims.py`: skipped; no T1 concurrency item.
- `refactoring-review.py verify`: pass; no oracle edits or non-refactor commits, growth ratio 0.2055 below the 0.3 limit.
- PIT: skipped under `verification/pit.md`; only Python verification tooling and its contract changed, with no Java production or Java mutation evidence changes.

## Proposed checks

None; no finding was mechanizable.

## Handoff to code review

- Review range: 06efaae43c0f343efc6e2bb6c289b907b218b9be..e4814ef9
- Refactor commits: e4814ef9
- Attack surface: preservation of literal contents, side-effect exclusions, JavaScript identity semantics, and deep-equality detection.
- Settled findings code review should not re-raise: R1-R4.
