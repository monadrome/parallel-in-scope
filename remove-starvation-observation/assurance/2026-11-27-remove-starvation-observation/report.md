# Assurance report: remove thread-pool starvation observation

Run `2026-11-27-remove-starvation-observation`; base `5d79d305`, frozen head `3e04f590` (removal
commit). Resolved inputs, isolation, and phase evidence are in `verification-summary.md` and
`run-manifest.json`.

The delivery branch adds later commits on top of the frozen head: documentation fixes from review, the
PIT record and its correction, one added core regression test, and the three promoted hidden-suite
tests. None changes library requirements or production code, and the test commits only add evidence,
so each is skip-eligible under the assurance routing policy and this report's frozen scope still covers
the semantic diff. The final lightweight review is asked to confirm that classification for every
later commit (`evidence/review-evidence.md`). Gate logs that ran later (full suite, consumers, docs,
race claims, final-head suite checks) were taken at the delivery branch head, which contains the frozen
head unchanged in production code.

## 1. Contracts the change commits to

The change removes the public observation API (C-1) and leaves these runtime commitments, with the
observation term gone from each: `close()` rejects new work, is idempotent, and never shuts down a
registered executor (C-2); `awaitQuiescence(Duration)` validates its argument, checks interruption
first, answers a non-positive timeout with one check, times out before `close()`, and returns `true`
only after admissions, undrained runs, execution exit, and framework-service shutdown (C-3);
`inFlight()` counts admissions and undrained runs only (C-4); `snapshot()` has four readings (C-5);
no starvation sampling thread exists (C-6); and an adopted source queued behind its own waiter ends
at the group deadline with a wait-graph self-loop and a cancelled source (C-7).

## 2. Contracts lacking detail

`contract-gaps.md`: no stated bound on service shutdown after the last run drains (G-1); no ordering
between counter reads and `close()` (G-2); thread names are not a documented contract (G-3); no
definition of impossible snapshot combinations (G-4); and the counting unit of `inFlight()` for a
synchronous call, which stays an admission until it returns (G-5, found by CONTRACT-TDD-007).

## 3. New evidence and its promotion

| Candidate | Contract | Evidence | Promoted as |
| --- | --- | --- | --- |
| CONTRACT-TDD-004 | C-3 | `awaitQuiescence(null)` throws `NullPointerException`; interrupt check precedes the state answer | C-0005 |
| CONTRACT-TDD-008 | C-4 | `inFlight()` is 0 while a cancelled execution still runs user code | C-0006 |
| CONTRACT-TDD-010 | C-5 | snapshot shows 0 undrained batches with an unexited execution signal | C-0007 |

Each passed twice on the real implementation, failed under a contrary production patch
(`reverse-verification/`), and was restored exactly. A fresh GLM-5.4 context adapted and promoted them;
the suite's metadata check, explicit run, and report check exited 0.

## 4. Duplicate, wrong, unstable, or ineligible candidates

Seven duplicates (TDD-001, 002, 003, 005, 006, 009, 012) with the covering tests named in
`candidate-status.csv`. TDD-007 is a wrong assumption: it expected `inFlight() == 1` during one
synchronous `Par.map`, but the call's admission spans the whole wait, so the value is 2 (G-5).
TDD-011 is an insufficient oracle: thread names are not a public contract, and it passes at base
because the removed scheduler created its thread only when sampling was scheduled. None was promoted.

## 5. Implementation defects versus test or documentation problems

No production defect was found. TDD-007 exposed a documentation gap (G-5), not a defect introduced
by this change. Focused PIT exposed one evidence gap: no test asserted that the timeout-action pool
shuts down at quiescence. `ParRuntimeTest.quiescenceRetiresTheTimeoutActionThreads` now covers it
and was reverse-verified.

## 6. Code fixes needed

None for this change.

## 7. Documentation or contract additions

- G-5: state in the `inFlight()` Javadoc and the user guide that a synchronous `map`, `mapAndCombine`,
  or `runAll()` stays an admission until it returns, or change the counter. Pre-existing; left for the
  maintainer.
- `ParRuntime.releases()` still has no production caller (also noted by the 2026-11-24 PIT record).
  The new regression test uses it as a probe of the timeout-action pool; a change that removes the
  method needs another probe.

## 8. Verification status

| Check | Status | Evidence |
| --- | --- | --- |
| full-suite | pass | `logs/040-full-suite.*` (`mvn -B -ntp -o clean install`, final head) |
| consumer | pass | `logs/041-consumer-*.*` and `logs/042-demo-*.*` on JDK 25 and JDK 8 |
| pit | pass with accepted exceptions | `evidence/pit/2026-11-27-remove-starvation-observation.md`, archived XML; four `TIMED_OUT` accepted below |
| independent-review | pass | two adversarial rounds on DeepSeek-V4-Pro and GLM-5.4 seats plus a final lightweight GLM-5.4 pass; `evidence/review-evidence.md` |
| docs | pass | `logs/043-docs.*` |
| race-claims | pass | `logs/044-race-claims.*` |
| refactoring-review | pass | `evidence/refactoring-review-report.md`; six detectors, no findings |
| regression-tests | pass | `logs/03*-regression-*` in the promotion worktree and `logs/045-regression-*` at the final head |

Raw PIT statuses for `ParRuntime*` at the final head: 103 `KILLED`, 4 `SURVIVED`, 2 `NO_COVERAGE`,
4 `TIMED_OUT`. Dispositions: two equivalent, four maintainer-accepted pre-existing gaps (2026-11-25),
four expected mutation-induced liveness timeouts. The four `TIMED_OUT` entries are accepted exceptions
under `EXPECTED_MUTATION_LIVENESS`: they reproduced in both focused runs of this record, the unmutated
tests passed in each run, the XML and logs are complete, the liveness mechanism is the one the
2026-11-24 record states for the same unchanged methods, and that record's independent review approved
the disposition. Accepted: yes; raw status kept as `TIMED_OUT`.

## 9. Remaining uncertainty

- C-6 is sampled at discrete points only, and it was not promoted.
- The four `TIMED_OUT` mutants rely on an approval given for the same unchanged code on 2026-11-24;
  no reviewer re-examined their mechanism in this run.
- The refactoring review left the deleted classes and the `ParRuntime` deletion hunks without
  per-lens detectors beyond the whole-diff M lens; the adversarial reviews covered those files.
- Kimi was unavailable as a seat (its CLI returned no output in the smoke test), so both reviewer
  families are DeepSeek and GLM through the Codex harness.

## Improvements, in order

1. Confirmed production defects: none.
2. Missing contracts: G-5 first, then G-1 and G-3.
3. Tests: the timeout-action retirement test and C-0005 to C-0007 are in place; a later change can
   add seams for the four accepted `ParRuntime` gaps.
4. Duplicates: seven duplicates were not promoted; no existing test needs merging.

Final gate: PASS WITH ACCEPTED EXCEPTIONS
