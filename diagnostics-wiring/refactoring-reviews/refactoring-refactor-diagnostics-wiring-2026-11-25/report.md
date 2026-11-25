# Refactoring review: diagnostics-wiring

- Base / behavior head: f093bb12f7695db32eaeb1e7e89cba2539944db7 / 72237bfbaf687741a6a7d48c94636b657c03babe
- Plan: per-lens, 6 detectors, lenses M C D S V T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.
  The only candidate, `TaskGroup#prepare` (cyclomatic 13), is pre-existing; this diff lowers its
  code lines by one.

## Detectors

Dispatched manually with the full brief text through
`codex exec -m kiro/gpt-5.6-terra --sandbox read-only` (the script's codex adapter would have
used the Magpie default `ctrip-ada/claude-auto`, the implementer's model family). `git status`
was identical before and after the run.

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex / kiro/gpt-5.6-terra | completed | 1 |
| C | C | codex / kiro/gpt-5.6-terra | completed | 0 |
| D | D | codex / kiro/gpt-5.6-terra | completed | 1 |
| S | S | codex / kiro/gpt-5.6-terra | completed | 0 |
| V | V | codex / kiro/gpt-5.6-terra | completed | 1 |
| T | T | codex / kiro/gpt-5.6-terra | completed | 0 |

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M07 | `ParRuntime#announcements` | T1 -> T1 | applied (27ff7b56) | Exposing the `Announcements` object made `Par` and `WorkloadFit` reach through the runtime. Restored `ParRuntime.announce(Runnable)` as the single facade over the one instance; `ExecutorWaitGraph` and the static `runtimeFor` keep the instance because they are built before the runtime finishes construction. |
| R2 | D01 | `Announcements#Announcements` | T1 -> rejected | rejected | Conventions require `checkNotNull` for null caller violations, internal declarations included; the check fails at construction instead of at the first diagnostic, which is the better failure point. |
| R3 | V05 | `Announcements#Announcements` | T0 -> T0 | applied (27ff7b56) | Added `@throws NullPointerException` to the constructor Javadoc. |

No finding matches an entry in `design/decision-log.md` or `docs/zh/design/idea-graveyard.md`.

## Verification

- spotless: pass (`mvn spotless:apply`, then `spotless:check` in preflight).
- targeted tests: pass, covered by the full suite.
- full `mvn test`: pass on 27ff7b56 (3446 + 2 tests, 0 failures).
- `check-race-claims.py`: pass in the committed-tree preflight of 72237bfb; R1 does not touch a
  concurrency route.
- `refactoring-review.py verify`: pass (growth 0.107 of limit 0.3; no oracle edits; no
  non-refactor commits).
- PIT: run as a focused scope over the changed classes; result recorded in the delivery ledger.

## Proposed checks

None. D01 was marked mechanizable, but it was rejected.

## Handoff to code review

- Review range: f093bb12..27ff7b56; refactor commits: 27ff7b56
- Attack surface: 27ff7b56 behavior preservation (same delivery instance and path); 72237bfb
  admission ordering (retain, then track, still inside `whileOpen` before submission at all four
  call sites) and the empty-list guard of the group overload.
- Settled findings code review should not re-raise: R1, R2, R3
