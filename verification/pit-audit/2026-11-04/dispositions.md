# Disposition ledger — P0034 PIT audit 2026-11-04

## Provenance And Count Reconciliation

Initial run: clean revision `604a4788` (production and tests), JDK 25.0.4.1,
PIT 1.30.1 with `pitest-junit5-plugin` 1.2.3, 8 threads,
`timeoutConstant=60000`, `timeoutFactor=1.25`, the full core mutator set including
`VOID_METHOD_CALLS`, no history, `targetTests=io.github.monadrome.parallelinscope.*`,
and `targetClasses` = `Checkpoints`, `CheckpointCancellation`,
`ExecutionPhaseHintFuture*`.

Final affected-scope run: HEAD `9484235e` (the docs-only commit on top of
`604a4788`) plus the uncommitted addition
`CheckpointsTest.durationCheckJoinWaitsForALiveTarget`. Production is byte-identical
to `604a4788` (`git diff 604a4788 9484235e -- src/main` is empty). The flags match
the initial run except `targetClasses=io.github.monadrome.parallelinscope.Checkpoints`.

Raw statuses come from the XML; PIT's console headline folds `TIMED_OUT` into
"Killed" and is not used here.

| Evidence | KILLED | SURVIVED | TIMED_OUT | NO_COVERAGE | Total |
|---|---:|---:|---:|---:|---:|
| `reports/initial-p0034.xml` | 139 | 16 | 5 | 3 | 163 |
| `reports/final-checkpoints.xml` | 75 | 11 | 1 | 0 | 87 |
| **Combined final** | **140** | **15** | **5** | **3** | **163** |

Combined final = initial `CheckpointCancellation` (2 `KILLED`) + initial
`ExecutionPhaseHintFuture*` (63 `KILLED`, 4 `SURVIVED`, 4 `TIMED_OUT`, 3
`NO_COVERAGE`) + final `Checkpoints` (75 `KILLED`, 11 `SURVIVED`, 1 `TIMED_OUT`).
The initial `Checkpoints` XML is superseded. Reusing the initial EPHF/CC entries is
sound: their production and tests are unchanged, and the added test exercises only
`Checkpoints.checkJoin(Thread, Duration)`.

## Baseline Comparison

Matched on class, method, mutator, description, and raw status against the five
2026-10-31 batch XMLs, all 24 initial non-killed entries are present; there is no
new P0034 non-killed entry. The only resolved baseline item is the
`checkJoin(Thread, Duration)` delegation (baseline `checkJoin` XML line 249), now
killed by the new test. The baseline `Checkpoints.sleep` survivor (old XML line
146) has no current site because P0034 removed that API. The 11 current
`Checkpoints` survivors are baseline E12 sites.

## Resolved Mutation

- **`Checkpoints.checkJoin(Thread, Duration)`**, XML line 217,
  `VoidMethodCallMutator`, "removed call to
  `io.github.monadrome.parallelinscope.Checkpoints::checkJoin`" — initial
  `SURVIVED`, final `KILLED` by
  `CheckpointsTest.durationCheckJoinWaitsForALiveTarget()`. Reverse verification:
  with only the inner delegation removed, the test failed
  `expected: TIMED_WAITING but was: TERMINATED`; after restore it passed. The
  surviving outer check on the same overload (XML line 216) is the E12 duplicate
  below.

## E — equivalent or redundant by proof

### E12 — `Checkpoints` delegating-overload outer checks (11, `SURVIVED`)

All rows are `io.github.monadrome.parallelinscope.Checkpoints`, status `SURVIVED`,
`VoidMethodCallMutator`, description "removed call to
`io.github.monadrome.parallelinscope.Checkpoints::checkCancellationToken`".

| XML line | Overload |
|---:|---|
| 140 | `checkAwait(CountDownLatch, Duration)` |
| 171 | `checkAwait(Condition, Duration)` |
| 216 | `checkJoin(Thread, Duration)` |
| 264 | `checkGet(Future, Duration)` |
| 327 | `checkSleep(Duration)` |
| 354 | `checkTryAcquire(Semaphore, Duration)` |
| 367 | `checkTryAcquire(Semaphore, long, TimeUnit)` |
| 380 | `checkTryAcquire(Semaphore, int, Duration)` |
| 410 | `checkTryLock(Lock, Duration)` |
| 437 | `checkAwaitTermination(ExecutorService)` |
| 449 | `checkAwaitTermination(ExecutorService, Duration)` |

Each method checks the token and then immediately delegates to another overload
that performs the same `checkCancellationToken()` before doing any work. For the
same token the delegate throws the same `CheckpointCancellation` with the same
attribution, so removing the outer check changes no supported outcome. The delegate
checks are all `KILLED` in the final XML by `CheckpointsCancelledEntryTest`:
`checkAwait` 153, `checkJoin` 228, `checkGet` 281, `checkSleep` 338,
`checkTryAcquire` 394, `checkTryLock` 423, `checkAwaitTermination` 462. This is
baseline E12; the baseline had 13 such sites, and the removed `sleep` API site plus
the now-killed `checkJoin` delegation account for the difference.

### E7 — `ExecutionPhaseHintFuture.run` park removal (XML line 257, `SURVIVED`)

`VoidMethodCallMutator`, "removed call to
`java/util/concurrent/locks/LockSupport::parkNanos`". The mutant keeps the
`while (interruptDeliveryInProgress)` loop and its exit condition; it changes only
the blocking strategy from parking to spinning. The contract — finish pending
delivery before the thread can be reused — is unchanged. Baseline E7.

### E8 — `ExecutionPhaseHintFuture.run` scope-restore removal (XML line 262, `SURVIVED`)

`VoidMethodCallMutator`, "removed call to
`io.github.monadrome.parallelinscope.SubmissionScope::restore`". Through every
current caller the inline run happens inside `TaskSubmissions.submitScoped`, whose
`finally` restores the same enclosing scope the mutant would lose; the only
observer of the intermediate window between `run()`'s exit and that restore is
same-thread library code with no public or user hook. Baseline E8.

## D / retained gaps (raw statuses retained)

### `ExecutionPhaseHintFuture.claimBody` true-return (XML line 187, `SURVIVED`)

`BooleanTrueReturnValsMutator`, "replaced boolean return with true for
`io.github.monadrome.parallelinscope.ExecutionPhaseHintFuture::claimBody`". The
false case requires the body slot to be terminal while `run()` already won the
`SUBMITTED→RUNNING` phase CAS; every terminal-slot transition
(`claimSubmissionFailure`, `afterDone`) is gated on winning that same CAS first, so
`claimRunning()` cannot return false on a path `run()` reaches. Defensive branch,
not claimed equivalent. Baseline disposition.

### `ExecutionPhaseHintFuture.claimSubmissionFailure` publish removal (XML line 171, `SURVIVED`)

`VoidMethodCallMutator`, "removed call to
`io.github.monadrome.parallelinscope.TaskObservation::publishSkipped`". On the
deterministic rejection path the skipped publication produces a snapshot
field-identical to the fallback settled snapshot (same outcome, zero timings, same
failure object). The distinguishing scenario is a cancellation racing the
rejection — the first-writer-wins race the early publication exists for — which no
deterministic harness interleaves. Retained gap; baseline disposition. Not claimed
equivalent.

### `ExecutionPhaseHintFuture.run` finally-copy terminal phase (XML line 269 ×3, `NO_COVERAGE`)

`VoidMethodCallMutator`, "removed call to
`java/util/concurrent/atomic/AtomicReference::set`". These are the three same-line
copies of the finally's `phase.set(ExecutionPhase.TERMINAL)`; javac duplicates the
finally and PIT cannot attribute coverage, logging: "Found more than one mutation
similar on same line in a finally block. Can't correct for inlining." The
observable contract is asserted behaviorally in `ExecutionPhaseHintFutureInlineTest`
by `thePhaseHintReachesTerminalAfterAFailingInlineRun`,
`thePhaseHintReachesTerminalAfterAFailingPoolRun`,
`thePhaseHintReachesTerminalAfterAFailureThatLosesToCancellation`, and
`thePhaseHintReachesTerminalAfterAnInlineViolationRejection`. These assertions do
not change the raw `NO_COVERAGE` status and are not counted as kills. Pre-existing
PIT finally/inlining limitation; retained.

## TIMED_OUT — five raw inconclusive entries

| Class | Method | XML line | Mutator | Description | Status |
|---|---|---|---:|---|---|
| `ExecutionPhaseHintFuture` | `run` | 219 | `NegateConditionalsMutator` | negated conditional | `TIMED_OUT` |
| `ExecutionPhaseHintFuture` | `run` | 230 | `NegateConditionalsMutator` | negated conditional | `TIMED_OUT` |
| `ExecutionPhaseHintFuture` | `run` | 230 | `NegateConditionalsMutator` | negated conditional | `TIMED_OUT` |
| `ExecutionPhaseHintFuture` | `run` | 230 | `NegateConditionalsMutator` | negated conditional | `TIMED_OUT` |
| `Checkpoints` | `checkPut` | 315 | `VoidMethodCallMutator` | removed call to `java/util/concurrent/BlockingQueue::put` | `TIMED_OUT` |

All five match the 2026-10-31 baseline by class, method, mutator, description, and
raw status (baseline `run` 211 and 222 ×3; `checkPut` 347). They are
mutation-induced hangs of bounded tests, not kills and not passes. Retained raw and
inconclusive; PIT's headline folds them into "Killed", this ledger does not.

## Fix Scope And Evidence Freshness

The only change is the test addition
`CheckpointsTest.durationCheckJoinWaitsForALiveTarget`; no production code changed.
The final `Checkpoints` rerun is current for that class. The initial
`CheckpointCancellation` and `ExecutionPhaseHintFuture*` entries remain current
because their production and tests are unchanged and the new test does not exercise
them. Final evidence is the union described above.

## Overall Status

**`inconclusive`** — not `pass`.

PIT work is complete for P0034: the scope and inputs are recorded, both runs are
fresh, all 23 final non-killed entries (15 `SURVIVED` + 5 `TIMED_OUT` + 3
`NO_COVERAGE`) have a disposition here, and no new P0034 survivor was found. The
raw run is not an all-green mutation pass: the five `TIMED_OUT` entries remain
inconclusive mutation-sensitive hangs and the three `NO_COVERAGE` entries remain a
PIT finally/inlining limitation. Neither group is counted as killed or as a pass.
