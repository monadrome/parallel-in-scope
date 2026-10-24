# Thread-pool starvation observation: workload-fit review and mutation records (2026-10-30 snapshot)

> Historical archive, not a live contract. Restored on 2026-10-31 from the
> 2026-10-30 revision of `design/thread-pool-starvation-observation.md` so the
> record no longer depends on a commit hash. The live contract is
> [../thread-pool-starvation-observation.md](../thread-pool-starvation-observation.md).

## Workload-fit review disposition

Commit `d71af957` (`feat!: make tasktype a workload-fit diagnostic`) was reviewed read-only by Kimi
(K3.1 Pro) and Codex (GPT-6.1-Sol, xhigh), each in its own remote-less clone, against the contract
items listed in the commit message. The implementer reproduced every retained finding against the
working tree with a regression test that failed on the reviewed commit before the fix.

| Finding | Disposition | Effect of the fix |
|---|---|---|
| Kimi F1 / Codex F1: a JUL handler that throws turns the submission-time mismatch warning into an admission failure; the body never runs (the 0.3 inert `rejectEnqueue` warning had the same path) | Retained, P0: contract violation, reproduced with public API only | Both warnings are delivered by `Announcements` on the runtime-owned executor that already delivers executor-wait cycle warnings, so the admitting thread never calls a handler; a failing or rejected delivery drops the message. `aFailingLogHandlerNeverRunsOnTheAdmittingThreadOrChangesTheOutcome` and `aFailingLogHandlerNeverChangesTheOutcomeOfAnInertRefusal` assert the outcome and the publishing thread. |
| Kimi F2 (P0) / Codex F2 (P2): racing first sightings of one pair each claim a slot, so the memory overflows before 1024 pairs and the summary overstates the count | Retained, downgraded to P2: no task outcome changes, but the bounded-memory contract is wrong | A pair claims a slot only after its insertion succeeds, and an insertion over capacity is rolled back; overflow now implies exactly 1024 stored pairs. `racingFirstSightingsOfOnePairClaimOneSlot` races four threads per pair for 1024 rounds. |
| Codex F3 (P2): no deterministic test pins "a sealed total never changes"; removing the commit guard passed the focused tests | Retained as a test gap; the implementation was correct | `aCommitAfterTheSealNeverChangesTheSealedTotal` commits after the seal and reseals. |
| Codex F4 (P3): guides and Javadoc said unreadable CPU time leaves "at best `INSUFFICIENT_VISIBILITY`" / "no verdict is possible", contradicting contract 6's priority | Retained | Javadoc, both guides, both migration guides, the changelog, and screening rule 6 state "never `NO_ISSUE`; `INSUFFICIENT_VISIBILITY` unless a higher-priority signal was captured". |
| Codex F5 (P3): "an entry or unit declared `UNKNOWN` contributes raw measurements only" contradicts the per-side verdict rule | Retained | Rule 4 and the report Javadoc say each side's `COMPUTE` enables only its own verdict; a measurement with neither side `COMPUTE` is raw only. |
| Kimi F3 (P3): `excludedBodies()` omitted bodies whose clock reading failed | Retained; also found in self-review | Javadoc, scope Javadoc, both guides, and rule 6 name both cases. |
| Self-review: `timing.stop()` ran first in `ScopedCallable.call()`'s cleanup, so a VM error from it skipped releasing the body and restoring the task context, leaving the worker thread with a finished task's context | Retained, P2 | Timing wraps only the body call in `callTimed`; `anErrorFromTheTimingStillRestoresTheWorkersTaskContext` checks the next task on the same worker. |
| Self-review: a window keeps one total per `(Par, unit name, unit type)` with no cap | Deferred to BACKLOG O-17 | Documented in the cost paragraph and both guides; a cap needs a new public accessor and a status rule, which is a maintainer decision. |
| Self-review: the three-argument `register` lacked `@since 0.4.0` | Retained, P3 | Added. |
| Codex, contract 1 note: a body registered before the seal still reads its clocks after close | Rejected as a finding: this is the documented start-eligibility semantics, and the sealed total ignores the late commit | None. |
| Kimi F1 suggestion: catch handler failures at the log call | Not adopted as proposed: catching alone still lets a blocking or re-entrant handler hold admission | Off-thread delivery, matching the executor wait graph. |

A second round reviewed fix commit `6903ff1b` in fresh sessions of both reviewers, with the table
above as the baseline. Both accepted the round-one dispositions overall.

| Finding | Disposition | Effect of the fix |
|---|---|---|
| Codex R2 F1 (P2): at the capacity boundary a duplicate saw a pair whose insertion was about to be rolled back and returned silent, so an admission of a pair that was never remembered went uncounted, and the set briefly held 1025 pairs; the class Javadoc disclosed this, the contract and the snapshot accessor did not | Retained; it also refutes the round-one classification of the `<` to `<=` survivor as equivalent | The memory is one immutable set replaced by compare-and-set, so every admission is decided against one state: a pair is remembered exactly when its insertion succeeds and the set never exceeds the capacity. `twoNewPairsRacingForTheLastSlotCountEveryAdmissionOfTheLoser` races two pairs for the last slot over 20,000 rounds with a package-private capacity seam and requires both admissions of the losing pair to be counted. |
| Codex R2 F2 (P3): the two off-thread log tests awaited the capture handler but asserted on the failing handler, which runs after it | Retained as a test-ordering flake | Both now await the records and the publishing thread in one condition. |
| Codex R2 F3 (P2): a failure creating the delivery thread — reproduced with a `SecurityManager` on JDK 8 and a throwing `InheritableThreadLocal.childValue` on JDK 25 — runs on the admitting thread and escaped, because only `RejectedExecutionException` was caught | Retained: contract violation through the shared helper, also reachable from executor-wait cycle warnings | `Announcements.deliver` drops any `RuntimeException` from `execute`. `AnnouncementsTest` and `anAnnouncementExecutorThatCannotStartADeliveryNeverChangesTheOutcome` cover it. |
| Kimi R2 F1 (P3): the `unlabeledPars()` Javadoc still said "ran measured bodies" | Retained | Javadoc now says "registered a body, counted or excluded"; `anUndeclaredEntryWhoseOnlyBodyLostItsReadingIsStillListedAsUnlabeled` pins it. |
| Kimi R2 F2 (P3): independent delivery can publish the overflow summary before the 1024th individual warning, while the summary said the mismatches "have been logged" | Retained for the wording; strict ordering rejected because the contract only makes logs best effort and ordering would need a serial delivery queue | The summary says the memory "is full at 1024 Par and unit-name pairs", and the contract and both guides say messages may appear out of admission order. |
| Kimi R2 residual note: a duplicate racing a rollback may read silent | Superseded by the Codex R2 F1 fix, which removes the race | None. |

A third round reviewed `2e39c029` in the same two sessions. Kimi reported no finding. Codex reported
one P3: the `suppressedTaskTypeMismatches()` Javadoc promised the count stays zero with fixed unit
names, but 1025 entries sharing one fixed name also overflow the per-runtime memory. Retained; the
Javadoc now ties zero to at most 1024 distinct mismatched pairs. Both reviewers accepted the earlier
dispositions and independently reproduced the reverse checks of the second-round fixes. Both named
the unbenchmarked cold-path cost of the copy-on-write memory, at most 1024 entries copied per new
pair, as a remaining risk rather than a defect; it is tracked with the other unmeasured costs in
BACKLOG O-16.

## Workload-fit mutation verification

- Reverse verification on the reviewed commit: reverting each fix alone made its tests fail — TaskType
  refusal in `SmartBlockingQueue`, combine type inheritance, nested type inheritance, the body timing
  hook, the admission mismatch call, and the lost-CPU flag.
- PIT on `d71af957` (core mutators plus `VOID_METHOD_CALLS`), scoped to `WorkloadFit`,
  `TaskTypeMismatches`, `SystemWorkloadClock`, `ThreadPoolStarvationReport`, and
  `SmartBlockingQueue`: 157 mutants, 143 killed, 9 survived, 5 without coverage. A run over `Par`'s
  admission diagnostics killed 13 of 13.
- Survivors classified as test gaps and closed by new tests: the negated window filter in
  `WorkloadFit.without` (closing one window removed the others), `Key.equals` made always-true
  (needs colliding hashes; `unitsWhoseNamesShareAHashCodeAreMeasuredApart`), and the
  `SystemWorkloadClock` disabled-timing results (`theSystemClockReportsDisabledCpuTimingAsUnavailable`,
  `theSystemClockReadsTheCallingThreadsCpuTime`).
- Survivors classified as race-only: removing the window-level `sealed.set(true)` and forcing
  `Cell.start()` to succeed differ only when a body registers concurrently with the seal; the
  published report is unchanged and no deterministic hook exists between registration and the seal.
- Equivalent: `lowCpu`'s `executionNanos > 0` boundary (monotonic clocks make the delta non-negative),
  `Key.hashCode` returning 0 (correctness rests on `equals`), and the `SmartBlockingQueue`
  constructor boundary (the delegate rejects the same capacity).
- Uncovered: the `SystemWorkloadClock` branches for a missing `java.management` module or a throwing
  bean, which a test cannot reach without replacing the platform bean.
- Reverse verification of the review fixes: reverting the off-thread delivery, the slot-after-insert
  rule, or the sealed-commit guard made its new test fail. The first version of the timing-error
  test passed against the reviewed `ScopedCallable` because its injected error hit the opening clock
  read, before any timing existed; the test now fails the closing read and fails against the
  reviewed commit.
- PIT after the fixes, same scope plus `Announcements`: 158 mutants, 146 killed, 7 survived, 5
  without coverage; `Par`'s admission diagnostics 13 of 13 and `ScopedCallable.callTimed` 3 of 3
  killed. The new survivors were in `TaskTypeMismatches.record`: a `null` return on the duplicate
  branch, killed after the race test asserted that no outcome is null (12 mutants, 11 killed in that
  rerun), and `stored.get() < CAPACITY` changed to `<=`. That survivor was first classified as
  equivalent; the second review showed it re-exposes pending keys under concurrency, so it was a
  concurrency test gap, and the compare-and-set rewrite removed the comparison. The remaining
  survivors and uncovered branches are those classified above; `Key.equals` returning true for a
  non-`Key` argument is unreachable because the map compares only keys.
- Second-round fixes: restoring the insert-then-rollback memory (with the capacity seam kept) fails
  `twoNewPairsRacingForTheLastSlotCountEveryAdmissionOfTheLoser`, and narrowing the delivery catch
  back to `RejectedExecutionException` fails `AnnouncementsTest` and the cannot-start-delivery test.
  PIT on the rewritten `TaskTypeMismatches` and `Announcements` killed 14 of 14 mutants.
