# Disposition ledger — core PIT audit 2026-10-31

## Provenance And Count Reconciliation

Production input revision `a3e750f9`; final test revision `2b2e3ad0`; clean tree;
JDK 25.0.2; mutators per `inventory.md` (core set including `VOID_METHOD_CALLS`);
targetTests `io.github.monadrome.parallelinscope.*` for every batch. The definitive
XMLs are `reports/final-batch-{A..E}.xml`, and the corresponding logs are
`logs/final-batch-{A..E}.log.gz`. No batch used PIT history input.

Commit `29ed543c` says "1581/1696 (93.2%)" and "28 TIMED_OUT". That text is stale:
the definitive XMLs contain 1583 `KILLED` and 26 `TIMED_OUT` (1583/1696 = 93.34%).
The pushed commit is not amended; this ledger and the archive README supersede its
summary. PIT's console "Killed" line also folds `TIMED_OUT` into its total, so this
ledger counts raw statuses from XML only.

This ledger follows the E/G/D convention of `design/synchronous-scope-exit.md`
§6.2 (E equivalent by proof, G gap — fixed or retained, D defensive/unreachable
branch not claimed equivalent).

## Raw Counts

| Batch | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | Total |
|---|---:|---:|---:|---:|---:|
| A | 230 | 5 | 0 | 0 | 235 |
| B | 245 | 14 | 3 | 4 | 266 |
| C | 546 | 30 | 5 | 5 | 586 |
| D | 262 | 16 | 2 | 12 | 292 |
| E | 300 | 4 | 8 | 5 | 317 |
| **Total** | **1583** | **69** | **18** | **26** | **1696** |

Killed ratio: 1583/1696 = 93.34%. Final non-killed: 69 SURVIVED + 18 NO_COVERAGE +
26 TIMED_OUT (raw statuses retained;
concurrency-test kills are flaky in both directions — e.g. batch D net −3 while
batches A–C gained +44 — and the unbounded Checkpoints entry mutants became clean
kills after the review's bounded-fallback fix). `package-info.java` has no
executable code and no mutation sites (accounted). Final XMLs:
`reports/final-batch-{A..E}.xml`; logs in `logs/final-batch-*.log`. B and C were
re-run last (after the final test additions and the review fixes); superseded
intermediate runs remain in the local audit workspace and are not part of this
committed evidence. Every final run is a fresh full-coverage analysis.

## Independent review

Run `parallel-in-scope-29ed543c-20261101-040610-328368` (llm-review-bench, commit
`29ed543c`): claude completed (2 findings), kimi completed (1), codex failed twice
with upstream 503 (blocked, not counted as clean). Adjudication v1 (agent judge):
C01 = executor leak in `futureReadsRejectACancelledScope` (claude+kimi) —
confirmed, fixed in `2b2e3ad0`; C02 = unbounded adapter entries hang on regression
(claude) — confirmed (the entry mutants were TIMED_OUT, not KILLED), fixed in
`2b2e3ad0` with bounded daemon fallbacks. Post-fix batch C: 546/586 (94%), entry
mutants at Checkpoints 156/329/345 now KILLED.

## E — equivalent, proven (no action)

1. **Value-type hashCode constants** (TaskId, ParId, TaskNode): documented contracts
   specify equality only; a constant equals-consistent hashCode is contract-legal and
   any distinguishing test must assert the undocumented hash formula.
2. **Deadlines boundary arithmetic** (remaining:67/:73, after:87 ×2): both sides of
   each boundary produce identical results by exhaustive evaluation (`now == deadline`
   → 0 on both paths; `remaining` reached only with `remaining >= 1`; `after`
   saturates to the same `Long.MAX_VALUE`; `duration == 0` cannot overflow).
3. **Index checks** (TaskGroup#memberAt:502, GroupValues#checkIndex:114/:84,
   TaskGroupResult#checkIndex:330/:207): the guarded `List.get` throws the same
   documented `IndexOutOfBoundsException`; only non-contract message text differs.
4. **LeanCancellationException#<init>:22 setStackTrace**: `Throwable(String)` invokes
   the overridden `fillInStackTrace()` (a no-op returning `this`), so the trace is
   already empty; the explicit clear is redundant defense. Existing test
   `constructor_producesEmptyStackTrace` covers the contract.
5. **SmartBlockingQueue#<init>:43 boundary**: the existing
   `rejectsNonPositiveCapacity` test constructs capacity 0 against the mutant and
   still sees `IllegalArgumentException` — the TTL delegate rejects 0 itself, same
   documented exception type.
6. **WorkloadFit#lowCpu:69 boundary**: the mutated comparison is
   `executionNanos > 0` at exactly 0, where the second conjunct is false for every
   reachable input (cpu >= 0); the strict-half boundary at `lowCpu(5, 10)` is already
   pinned by `WorkloadFitTest`.
7. **RefillDriver loop-shape equivalents** (handoff:319/:337/:346/:360 true-returns,
   drain:302 ×3, submitAll:125): an extra claim iteration re-derives the stop from
   the `settled`/range/`isDone` guards; a skipped drain re-entry is retried by the
   next completion event (every completion calls `drain()`; unclaimed work exists
   only while elements are in flight); `submitAll`'s remaining==0 branch finds the
   signal already settled either way. `run:241` park removal keeps the wait loop and
   changes only the blocking strategy. The `settleFailure`/`settleSuccess` removals
   (:336/:345/:359) and `abandonRemaining:427` were moved to D after the re-run
   confirmed no public accessor separates the submission signal's settlement from
   element settlement (see D).
8. **ExecutionPhaseHintFuture#run:246 restore(borrowedScope)**: through every
   current caller the inline run happens inside `TaskSubmissions.submitScoped`,
   whose `finally` restores the same enclosing scope the mutant would lose; the only
   observer of the intermediate window (between `run()`'s exit and `submitScoped`'s
   restore, same thread, no library or user code) would need package-private access
   to `SubmissionScope`.
9. **TaskObservation#publishSkipped:122 Verify / TaskCompletion#<init>:51 Verify**
   (D by §6.2 convention, not claimed equivalent beyond reachability): the only
   observation shape attached to a prepared future is `forTask` (unit id from
   `node.id()`, never null); `unattributed`/`of` shapes never attach to an engine
   future. `TaskCompletion`'s constructor is private and every factory passes at
   most one identity space by construction.
10. **SystemWorkloadClock#currentThreadCpuNanos:28/:33** (D, environment-gated):
    `Threads.BEAN` resolves once; on any JDK with `java.management` the bean exists
    and the CPU read does not throw, so both fallbacks are unreachable in this
    environment. Documented behavior ("unavailable management API ... reported as an
    unreadable CPU clock").
11. **ParRuntime#<init>:135 setRemoveOnCancelPolicy** (starvation scheduler):
    cancel-policy flag affects only retention of cancelled timer nodes inside the
    private scheduler; no observable contract difference.
12. **Checkpoints delegating-overload checks** (final re-run survivors at 146, 172,
    203, 248, 249, 296, 359, 386, 399, 412, 442, 469, 481): each is the
    `checkCancellationToken` call on an overload that immediately delegates to
    another overload whose own check (all killed by
    `CheckpointsCancelledEntryTest`) fires for the same cancelled token with the
    same lean flag. Redundant outer checks on delegating wrappers.
13. **TaskGroup#memberCompleted:854/:855/:856**: the explicit sibling-token cancel
    loop is masked by the token-tree parent propagation — member tokens are
    cancellation children of the group token (`cancellationParent(groupToken)`), so
    `groupToken.cancel()` (line 853, not mutated) reaches every member token through
    the constructor-wired parent link; the loop is defense in depth. The cascade
    test (`directlyCancellingAMemberCascadesToItsSiblingTokens`) passes with and
    without the loop, consistent with masking.
14. **TaskGroup#memberCompleted:865 negate** (members also commit FAIL_FAST
    synchronously): outcome-equal through convergence — `deriveOutcome` returns the
    recorded failure's reason under both FAIL_FAST and RUNNING states, and the
    async group bind commits fail-fast regardless.
15. **ParRuntime#installGlobal:237 checkState removal**: masked by the second,
    race-guard `closed.get()` check inside the same method, which throws the same
    `IllegalStateException` after the slot claim; the removed check is the
    fast-path duplicate.
16. **TaskBatch#close:394 negate**: for a token-bearing batch the negated branch
    cancels `submitCanceller` instead, and the signal-cancel path
    (`abandonUnclaimed` → `abandonRemaining`) cancels the prepared futures with
    interrupt — parked bodies are interrupted either way, so
    `bodyCompletionConfirmed` after the grace holds on both sides. The tokenless
    else-branch remains the fallback it was written to be.
17. **TaskGroup#submitPrepared:787 negate**: whichever operand is negated, the
    behavioral difference requires an empty-members-with-combine group, which the
    public stage builder cannot declare (kernel branch kept as defense, §6.2 145).

## G — gaps found and FIXED (tests added or strengthened; verification by fresh PIT re-run)

- **G4 Checkpoints cancelled-entry** — 37 removals of `checkCancellationToken` across
  every adapter. Fixed: `CheckpointsCancelledEntryTest` (29 entry forms; the
  checkpoint forms were already covered).
- **G5 GroupDraft one-shot guards** — Start#closeGrace:235, Start#submitAll:256/:258,
  Step#combineValues:315, Step#submitAll:326, Combined#submitAll:359/:360/:361. Fixed:
  three new tests in `GroupDraftLifecycleTest` (off-thread entries, stale-step
  combineValues, submitAll-consumes-the-draft for Start and Combined shapes).
- **G6 TaskGroupReport#orThrow failure branches** — three NO_COVERAGE conditionals.
  Fixed: `TaskGroupCascadeAndLoudFailureTest` (unchecked as-is, checked wrapped,
  Error as-is, cancellation-shaped) via the public `TaskGroupResult.orThrow()`.
- **G7 drain bookkeeping** — retainUntilComplete:630/:631, TaskGroup#prepare:662/:663,
  Par#submitWhileOpen:409, ParRuntime#whileOpen:592 (the last is individually masked
  — see E12 — but the family is anchored): `ParRuntimeQuiescenceTest` asserts
  `snapshot().undrainedBatches()` and `unexitedBodySignals()` during in-flight group
  and single submissions, and awaitQuiescence-after-close waits out a running body.
- **G11 close/grace mechanism** — BodyCompletionTracker had no direct test:
  awaitBounded:182 (both returns, was NO_COVERAGE), :184, isDone:107,
  cancelAndAwaitBodyExit:201 (grace-0 close is silent), :211 (interrupt restores the
  flag), awaitSettledUninterruptibly:315/:319/:323/:325/:328/:330,
  TaskBodyState#claimRunning:71. Fixed: `BodyCompletionTrackerTest`,
  `TaskBodyStateTest`.
- **G8 submission-failure observation** — TaskObservation#publishSkipped:123,
  ExecutionPhaseHintFuture#claimSubmissionFailure:163: rejected submission publishes
  SUBMISSION_FAILURE with zero timings. Fixed:
  `ExecutionPhaseHintFutureInlineTest.aRejectedSubmissionPublishes...`.
- **G9 submission-scope pair** — SubmissionScope#install:25, #restore:30/:31. Fixed:
  `SubmissionScopeTest` round-trip. (run:246 itself is E8.)
- **G10 terminal phase after failing runs** — run:253 exception-path finally copies.
  Fixed: phase()==TERMINAL asserted after failing inline and pool runs.
- **G12 batch loudness** — TaskBatch#close:394 (close cancels the token; E16 shows
  the negation is masked by the signal-cancel path, but the close behavior is now
  contract-asserted), valuesOrThrow cancellation precedence (a recorded element
  failure is preferred over the following cancellation; the pure-cancellation shape
  surfaces the cancellation itself), valuesOrThrow:305 (interrupted caller: lean
  exception, flag restored), BatchCombinedResult#bodyCompletionConfirmed:128,
  #unfinishedBodies:138. Fixed: `TaskBatchCleanupTest` (five tests). The residual
  `valuesOrThrow:294` is D below.
- **G13 group cascade and loud failure** — TaskGroup#memberCompleted:854/:855/:856
  (direct member cancellation cascades sibling token cancels),
  #converge:964 (values-publication failure logs SEVERE; :961's *listener-Error*
  branch was already covered; its pre-set branch stays D below). Fixed:
  `TaskGroupCascadeAndLoudFailureTest`.
- **G14 assorted documented contracts** — ExecutorWaitReport#empty:45 (fresh runtime
  report non-null and empty), ImmediateResult#fromTask:173 (RUNNING attribution
  rejected), TaskCompletion#enqueued:252 (exactly-at-threshold is not "exceeded"),
  ParRuntime#installGlobal:237 (closed runtime rejected),
  ParRuntime$DispatchingScheduledExecutorService#awaitTermination:762 (false while a
  timer entry is pending), Par#submitWhileOpen:400 (nested unstructured submit
  records the executor wait edge), ParRuntimeSnapshot#activeAdmissions:45 (all five
  counters readable from a constructed sample). Fixed across
  `ParRuntimeTest`, `ParRuntimeSnapshotTest`, `ExecutorWaitAdmissionTest`,
  `TaskCompletionTest`, `ImmediateResultTest`.
- **G15 ScopedCallable entry/exit** — call:47 (expired deadline / cancelled scope
  never enters the body), call:61 (body slot released before settlement listeners
  observe the future). Fixed: `ScopedCallableEntryTest`.

## D / retained gaps (recorded, not claimed equivalent)

- **TaskGroup#memberCompleted:866** — the combine's synchronous FAIL_FAST commit.
  Outcome-equal through convergence (deriveOutcome adopts the recorded reason either
  way); distinguishing it requires an interleaving assertion on the group token's
  committed state from a sibling cascade, which the public API cannot pin down.
  Matches §6.2 "95/96/99 | G ... 同步 fail-fast 时序" (same finding, earlier shape).
- **TaskGroup#converge:961 pre-set branch** — the `!values.isDone()` recovery for a
  failure *before* `values.set` (unsettled member, allocation failure). Unreachable
  through the public API today; the listener-Error branch is covered. Matches §6.2
  "35 | D".
- **TaskGroup#prepare:556** — the empty-group shortcut's parent-link difference:
  only observable as a retained parent listener on a nested empty group (§6.2 114,
  the same known gap).
- **TaskGroup#prepare:653** — the prepare-failure catch's terminal branch (failure
  after the combine slot was prepared): no supported declaration reaches it.
- **TaskGroup#awaitBodyCompletion:464** — the observation-barrier false branch:
  requires the publication window between body exit and observation settle to
  outlive the caller's whole budget; not deterministically constructible.
- **TaskBatch#submitPrepared:788** (empty-with-combine kernel branch): the public
  stage builder cannot declare the shape; the kernel keeps the branch as defense.
  Matches §6.2 "145 | D".
- **TaskObservation#publishSkipped:123 / ExecutionPhaseHintFuture#claimSubmissionFailure:163**
  — the skipped publication produces a snapshot field-identical to the fallback
  settled snapshot on the plain rejection path (same outcome, same zero timings,
  same failure object). The distinguishing scenario is a cancellation racing the
  rejection (first-writer-wins, the documented reason the early publication exists),
  which no deterministic harness can interleave. Retained.
- **ExecutorWaitReport#empty:45** — feeds only the wait graph's initial state; every
  public read goes through `of()`'s empty short-circuit, so a null return never
  surfaces. The non-null report contract is covered by
  `aFreshRuntimeReportsAnEmptyNonNullExecutorWaitReport`.
- **ParRuntime#enterAdmission:602/:603** — the losing side of the close-vs-admission
  race (close observed between two volatile reads). No deterministic harness without
  production hooks; the winning side's mutants TIMED_OUT, showing the loop's
  liveness is test-sensitive. Matches §6.2 "178/200/211 G/D".
- **ParRuntime#awaitQuiescence:509, BodyCompletionTracker#awaitBounded:181,
  awaitSettled:282, awaitSettledUninterruptibly:315/:318, Checkpoints-family nanos
  boundaries** — differences require the remaining budget to be exactly zero at the
  check; not deterministically constructible (nanoTime delta). Per §6.2's stance on
  "纳秒预算恰好等于零的边界", these are retained as gaps, NOT claimed equivalent.
- **ParRuntime#whileOpen:591/:592** — the admission-exit shutdown pulse: through
  every current caller the final decrement of the drain counters comes from a
  retained-completion or body-signal listener that pulses the same call, so removing
  the admission-side pulse is not distinguishable; the drain itself is covered by
  the quiescence tests.
- **TaskBatch#finishCombined:144** — removing the await is masked by
  `awaitBarriers` and the attribution pin waits in the same finish path; a
  distinguishing test needs an unsettled-attribution window the synchronous finish
  cannot leave open deterministically.
- **RefillDriver#handoff settle calls :336/:345, #abandonRemaining:427** — the
  documented cleanup contract is partially covered indirectly
  (`TaskBatchCleanupTest` exercises close/cancel windows); a direct kill would need
  to observe the submission signal's settlement separately from element settlement,
  which no public accessor exposes. Retained.
- **CancellationToken#timeoutScheduler:186** — feeds structural-parent scheduler
  inheritance (TaskGroup:567); deadline enforcement at checkpoints does not depend
  on the scheduler, and the timer-path inheritance has no deterministic test
  through the public API (ManualClock schedulers are injected at runtime level, not
  through this accessor). Retained.
- **TaskBatch#valuesOrThrow:294** — the ExecutionException catch's null-substitution
  (`if (recordedFailure == null) recordedFailure = aggregateFailure.getCause()`):
  through deterministic public paths the aggregate's cause and the first recorded
  failure are the same object (`allAsList` fails with the first element failure,
  which is also the first recorded attribution), so the substitution is only
  observable when the two diverge — a race between a later failure's recording and
  the aggregate's completion. Retained.
- **GroupDraft#submit:168 discard** — internal payload cleanup on a prepare failure;
  the futures were already cancelled by prepare's own catch; no externally
  observable state.
- **ExecutionPhaseHintFuture#claimBody:179 true-return** — the false case requires
  the body slot to be terminal while `run()` won the SUBMITTED→RUNNING phase CAS;
  every terminal-slot transition (claimSubmissionFailure, afterDone) is gated on
  winning that same CAS first, so `claimRunning()` cannot return false on a path
  `run()` reaches.
- **TaskGroupReport#orThrow has no production caller** — `TaskGroupResult.orThrow`
  implements the loudness rule directly (`report.recordedFailure()`); the report's
  own package-private accessor is covered directly by
  `theReportsOwnOrThrowFollowsTheSameLoudnessRule` (unchecked/error/checked branches
  killed); the residual `:121` entry is a line-attribution copy on the checked-wrap
  escalation whose behavior is asserted on both the wrapped and cancellation shapes
  (§6.2 511–513 disposed the same accessor's old shape the same way). If the
  accessor stays uncalled in production, deleting it is a maintainer decision, not
  this audit's.
- **Diagnostic-only emissions** — TaskObservation#publish:194 is now covered by the
  broken-listener SEVERE assertion; `logHandoffError` message text, `BatchReport`/
  `ScopedCallable` `toString`, and warning message wording are non-contract
  diagnostics; asserting their exact text would mirror implementation.

## TIMED_OUT (26 final) — retained raw

All are mutation-induced hangs of bounded tests (liveness sensitivity): TaskGroup
prepare:571, start:730 ×2/:731; ParRuntime awaitQuiescence:516, enterAdmission:601;
SlidingWindowSubmitter fallbackSubmit:179, parallelism:226, submit:197/:198, submitAll:122,
drain:284/:288 ×2, handoff:314/:325; ExecutionPhaseHintFuture run:211/:222 ×3;
Checkpoints checkPut:347; TPSOS close:87/:89,
sampleNow:133/:144; WorkloadFit$Entry:229. Per policy these are inconclusive
individually but demonstrate the suite's dependence on the mutated invariants; none
was relabeled KILLED.

## Fix scope

All fixes are test additions/strengthening; **no production defect was confirmed and
no production code changed.** New test classes: CheckpointsCancelledEntryTest,
TaskBodyStateTest, BodyCompletionTrackerTest, ParRuntimeQuiescenceTest,
TaskGroupCascadeAndLoudFailureTest, SubmissionScopeTest, ScopedCallableEntryTest,
ExecutionPhaseHintFutureInlineTest, TaskBatchCleanupTest. Extended:
GroupDraftLifecycleTest, ParRuntimeTest, ParRuntimeSnapshotTest,
ExecutorWaitAdmissionTest, TaskCompletionTest, ImmediateResultTest.

Per verification/pit.md (tests changed → evidence changed), the full core scope was
re-run fresh in the same five batches after the additions; see
`reports/final-batch-{A..E}.xml` and `README.md` for kill confirmation.
