# Thread-Pool Starvation Observation Contract

This contract covers the opt-in runtime signal for work submitted directly to a registered
executor. It complements [task-graph.md](task-graph.md): the runtime's executor wait graph records
structural waits-for cycles from library admissions, while this observation covers unstructured
submissions that never record a wait. The same window also screens library-admitted task bodies for
workload fit against their `TaskType` declarations; see [Workload-fit screening](#workload-fit-screening).

## User shape

Before this capability, direct submissions had no library diagnostic path:

```java
pool.execute(requestHandler);
```

The failure was a queue that stayed non-empty behind all busy workers while the caller only saw a
timeout or stalled request. The same work can now be observed explicitly. Run this bounded diagnostic
window on an application control thread independent of the request workers; the bounded wait
keeps sampling active and must not be added to request handling:

```java
ListenableFuture<ThreadPoolStarvationReport> reportFuture;
Duration samplingInterval = Duration.ofSeconds(1);
try (ThreadPoolStarvationObservationScope observation =
        runtime.openThreadPoolStarvationObservation(samplingInterval)) {
    reportFuture = observation.reportFuture();
    pool.execute(requestHandler);
    submitOtherDirectWork(pool);
    TimeUnit.MILLISECONDS.sleep(samplingInterval.multipliedBy(10).toMillis());
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
```

The example assumes queued submission. `CallerRunsPolicy` can run a handler inline; in that case
keep the diagnostic control thread separate from submission as well as from request workers.
With queued submission the control thread closes the window even if the handler stalls. Interruption also
closes the scope through try-with-resources; propagate `InterruptedException` or restore the interrupt
flag according to the control thread's policy. Submitting work and immediately closing the scope is
not a diagnostic window. Reports publish only at close, not while periodic sampling is running.
The example keeps the window open for ten configured intervals. An early or interrupted close can
publish `NO_ISSUE` without a qualifying sample pair; it is not a health assertion. A sampling `Error`
is rethrown after report publication; callers needing that report on the error path should retain
the future before close and inspect it in their error handler.

The report identifies the pool and captures the pool size, active workers, queue depth, completed
task count, and the number of no-progress sample pairs supporting the signal. It also records the
requested sampling interval and any sampling failure.

Earlier development snapshots could report a stalled pool immediately after opening and closing a
scope, without waiting an interval, or silently stop sampling after an exception. The same user code
now requires a real elapsed interval and exposes sampling failures. Migrate development consumers
from `evidence.stagnantSamples()` to `evidence.stagnantIntervals()`, use an interval of at least 10
milliseconds, and handle `SAMPLING_FAILED` with `failureMessage()`. No compatibility alias is retained
for this unreleased API because the old name counted pairs rather than samples.

### Workload-fit screening user shape

Before this capability, a body that blocked inside a compute pool had no library signal, and the
declaration meant to describe it changed execution instead:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool)
        .build();
runtime.par(ParId.of("cpu")).map(items, this::transform,
        BatchOptions.timeout("transform", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND));
```

The failure was a few bodies blocked on hidden JDBC, synchronous logging, or a later-added RPC
holding the few threads of a compute pool: same-pool work queued, latency rose, and CPU stayed low.
`CPU_BOUND` did not report it, and on a `SmartBlockingQueue` pool it made the queue refuse every
element. The same code now declares both sides without changing execution and is screened inside
a starvation observation window:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)
        .build();
try (ThreadPoolStarvationObservationScope observation =
        runtime.openThreadPoolStarvationObservation(Duration.ofSeconds(1))) {
    reportFuture = observation.reportFuture();
    runtime.par(ParId.of("cpu")).map(items, this::transform,
            BatchOptions.timeout("transform", Duration.ofSeconds(5)));
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
// LOW_CPU_UTILIZATION: WorkloadMeasurement{par='cpu', unit='transform', ..., issue=true}
```

The unit needs no declaration of its own: the entry's `COMPUTE` declaration covers every body
submitted through it, including bodies whose author did not know they block. Submission-time
comparison of the two declarations is part of
[task-group-api-and-options.md](task-group-api-and-options.md) §3.2.1.

## Contract

1. The observation MUST be opt-in. Creating a `ParRuntime` or using existing `Par`, `TaskGroup`,
   and `ParRuntime.executorWaitReport()` APIs MUST NOT start sampling.
2. The observation MUST NOT wrap, submit, cancel, interrupt, reorder, or otherwise alter user
   tasks. The registered executors remain borrowed resources owned by the application. Workload
   timing runs inside the library's own task wrapper of library-admitted bodies only.
3. Each distinct physical executor visible through the registered runtime entry MUST be sampled at
   the requested interval, which MUST be at least 10 milliseconds. Multiple `ParId` entries for one
   physical executor MUST share one observation and render their labels together. Sampling MUST run
   on a daemon scheduler independent of the deadline timer and MUST use fixed-delay scheduling.
4. A visible executor MAY produce `POTENTIAL_STARVATION` only after a pair of samples whose monotonic
   timestamps are at least one requested interval apart show a positive pool size, every current
   worker active, queued work present, and no increase in the executor's completed-task count.
   `Evidence` reports the number of qualifying stagnant intervals. Evidence is a snapshot of a risk
   captured during the observation window and is sticky until report publication; recovery does not
   clear it. Inspect the pool again when close-time state matters.
5. `POTENTIAL_STARVATION` MUST remain a risk signal. It MUST NOT claim a dependency cycle, prove
   that a worker is blocked, or change task outcomes.
6. A registered executor that cannot be inspected as a `ThreadPoolExecutor` MUST be named in
   `unsupportedExecutors()`. Status priority MUST be evidence (`POTENTIAL_STARVATION`), a
   workload-fit issue (`LOW_CPU_UTILIZATION`), sampling failure (`SAMPLING_FAILED`), unsupported
   executors or unreadable thread CPU time (`INSUFFICIENT_VISIBILITY`), then `NO_ISSUE`.
   `NO_ISSUE` MUST mean all registered executors were visible, thread CPU time was readable,
   sampling did not fail, and neither evidence nor a workload-fit issue was captured.
   `anyIssue()` MUST be true exactly for `POTENTIAL_STARVATION` and `LOW_CPU_UTILIZATION`. A sampling failure MUST be retained in `failureMessage()`; a
   scheduled sampling action MUST catch `Throwable`, record the first failure, and cancel future
   sampling. Closing after a sampling failure MUST publish a normal report; a final-sample `Error`
   or previously recorded sampling `Error` is rethrown only after that report is published.
7. The report future MUST be read-only, publish exactly once when `close()` normally returns, and
   remain available after close. Concurrent close calls MUST not publish a second report. Closing
   MUST wait for an in-progress sample before performing its final sample and MUST stop subsequent
   scheduled sampling; a final sample less than one interval after the prior sample MUST NOT add a
   stagnant interval.
8. Closing the runtime MUST wait to retire both framework schedulers until open starvation
   observations have published. Closing an observation MUST release that retention. The observation
   MUST NOT make the runtime close a registered executor. `ParRuntime.close()` SHOULD warn when open
   observations retain its services.
9. Sampling and report publication MUST use Java 8 APIs and MUST preserve caller interruption
   state when a concurrent close waits for publication. `samples()` MUST count completed samples,
   and `samplingInterval()` MUST expose the requested interval.

## Workload-fit screening

While a scope is open, every library-admitted task body records its thread CPU time
(`ThreadMXBean.getCurrentThreadCpuTime()`) and its execution time, excluding time spent queued.
The declarations it is screened against are in
[task-group-api-and-options.md](task-group-api-and-options.md) §3.2.1.

1. CPU and wall-clock reads MUST happen only for bodies inside an open window; outside every window
   a body MUST cost at most one volatile read. Opening a window MUST happen-before the reads of
   every body that starts after `openThreadPoolStarvationObservation` returns, and the aggregated
   result MUST happen-before completion of `reportFuture`.
2. A body MUST register in the windows open when it starts, read both clocks after registering and
   before committing, and count in a window only when its registration and its commit both precede
   that window's seal. Registration, commit, and seal MUST each be one atomic update of a per-unit
   immutable total, so a published measurement is one state: a body registered but not committed at
   the seal is counted in `excludedBodies()`, and a body registering after the seal is not counted.
   A sealed total MUST NOT change, so a published report is never modified.
3. Measurement MUST cover only library-admitted bodies and MUST NOT wrap or change tasks submitted
   directly to an executor. A measurement MUST be attributed to the submitting
   `(runtime, Par, unit name, unit TaskType)`; entries or runtimes sharing one physical pool are
   measured apart, and nothing MUST be inferred about the whole pool or about other submitters.
4. A measurement has enough samples at 20 counted bodies or one second of execution time, and is low
   when CPU time is below half the execution time. These are internal calibration values and MUST
   NOT be documented as stable across GC, hardware, or container environments. A low measurement
   with enough samples is an `issue()` when the entry is declared `COMPUTE`, whatever the unit
   declares, and `suspectedDeclarationDrift()` when the unit is declared `COMPUTE`; both can hold.
   Drift alone MUST NOT change the status. Each side's `COMPUTE` declaration enables only its own
   verdict, so a measurement where neither side is declared `COMPUTE` is a raw measurement only.
   `unlabeledPars()` MUST name every entry declared `UNKNOWN` that registered a body in the window.
5. Workload evidence MUST read as a screening lead: low CPU use may come from waiting on I/O, a lock,
   or nested work, or from runtime pauses or CPU throttling. It MUST NOT claim proven blocking or
   misuse. A body that waits synchronously for a nested batch holds its own thread while it waits;
   the report MUST NOT conclude that the nested batch's pool was chosen wrongly. Same-pool nested
   waits remain the executor wait graph's responsibility.
6. When thread CPU time is unsupported, disabled when the window opens, or unreadable during it,
   `cpuTimeAvailable()` MUST be false and the report MUST NOT be `NO_ISSUE`; contract 6's priority
   still lets starvation evidence, a low-CPU issue from earlier samples, or a sampling failure rank
   above `INSUFFICIENT_VISIBILITY`. The library MUST NOT call `setThreadCpuTimeEnabled`. A failed
   reading drops that body's sample, which then counts in `excludedBodies()`.
7. Workload diagnostics MUST only report. They MUST NOT fail or replace the result of a task, batch,
   or group.

Inside a window each body costs two thread-CPU and two wall-clock reads plus one atomic update of
its unit total at start and at end, per open window. The overhead of the CPU-clock read was not
measured with a benchmark; it is confined to the opt-in window. A window keeps one total per
`(Par, unit name, unit TaskType)` until it closes and has no cap, so unit names built per call grow
a long-lived window without bound (BACKLOG O-17).

## Limits and rejected alternatives

The signal intentionally uses executor counters rather than thread-stack inspection. Java 8 does
not expose a stable mapping from `ThreadPoolExecutor` workers to arbitrary application wait
objects, and stack heuristics would produce version- and framework-specific false negatives. The
counters are pool-wide: library tasks and direct `execute` tasks count equally, and a long CPU-bound
or slow I/O library task can therefore produce a false positive.

`NO_ISSUE` does not mean there is no deadlock. The signal cannot see all workers blocked with an
empty queue, `SynchronousQueue` or other zero-capacity handoff pools, pools not registered with this
runtime, or a pool where some workers are permanently blocked while other workers keep completing
short tasks. It reports no dependency edge between direct work and library work, and it does not
explain who waits for whom across pools or runtimes.

Submission ancestry is not a synchronous wait: projecting fire-and-forget submissions can invent
cycles. Task dependencies can be acyclic while parents occupy all workers needed by queued children;
the missing resource is executor capacity. The library's executor wait graph identifies this
capacity-cycle risk for known synchronous waits.
Direct submissions do not supply those wait edges. Multiple acyclic wait chains sharing a finite pool do
not alone prove starvation either: the awaited work may still acquire capacity and finish. An
expandable pool can relieve its own worker shortage without relieving a bounded pool elsewhere in
the chain, and cannot resolve a logical wait cycle or a held lock. Structural graph evidence and
pool-wide no-progress evidence therefore remain complementary risk signals, not deadlock proofs.
This observation adds no general submit/wait wrapper or explicit wait-edge API.

Opening an observation does not add cancellation, deadline, TTL propagation, `SmartBlockingQueue`,
or `rejectEnqueue` admission semantics to direct tasks. Only TTL wrappers are unwrapped; Guava or
custom executor decorators must be registered through their physical pool or they appear in
`unsupportedExecutors()`.

An open observation retains the framework schedulers after runtime close. Forgetting to close one
keeps `timerService` alive, makes `awaitQuiescence` return false, and allows sampling to continue
after runtime shutdown. Prefer try-with-resources or one long-lived scope for a diagnostic window,
not one scope per request. No automatic timeout closes a scope.
Closing waits for an in-progress counter read without a timeout; a blocking custom queue can block
close indefinitely. Adding a bounded close while a sampler still mutates its evidence would require
a separate publication protocol and is outside this hardening change (F13).

The observation does not add an executor decorator, future wrapper, or dependency-edge API. Those
shapes would create a second execution pipeline and conflict with the extension boundary in
[extension-and-wrapping.md](extension-and-wrapping.md). The executor wait report remains the
source of structural cycle evidence.

Workload-fit screening detects a blocking body on a compute entry at run time, but a compute body on
a blocking entry only through the submission-time comparison, when both sides are declared. CPU
saturation of a blocking pool may be a legitimate peak and does not prove too many runnable threads,
and Java 8 `availableProcessors` does not reliably reflect container quotas. Judging that direction
at run time would need platform-specific runnable-concurrency or context-switch evidence. Thread
state sampling was rejected as the primary signal because a blocking socket read is `RUNNABLE` in
the JVM, which misses the most common I/O wait. Starvation evidence and workload measurements stay
two typed lists rather than one evidence type with a kind tag: they share no field, so a tagged type
would carry meaningless pool counters on CPU evidence and the reverse. Each entry's declared type is
read through `Par.taskType()` rather than copied into `ParRuntimeSnapshot`, which samples drain
counters; the snapshot carries only the mismatch overflow count. A read-write lock around the seal
was rejected in favor of per-unit atomic totals: one compare-and-set per registration, commit, and
seal gives the same linearization without blocking a body. Historical source: `proposal:P0025`
(`P0025-workload-fit-diagnostics`; the same number also names the landed
`P0025-internal-cancellation-token`).

## Independent review disposition

- The first review found that evidence was frozen at the first stagnant sample, making the exposed
  consecutive-sample count misleading. The implementation now refreshes the immutable evidence
  value on each consecutive stagnant sample, so the published count is the last observed run.
- The first review found that the `inFlight()` and snapshot retention change was absent from the
  release notes. The unreleased changelog now names both accessors and the shutdown implication.
- T1/T2/T3/T4/T5 now have direct coverage: physical-pool/TTL label merging, real-interval and sampling
  failure regressions, simultaneous close/publication and interruption, validation/multiple scopes,
  and a queue-gated progress test that does not rely on finishing inside a short sampling interval.
- A cosmetic variable-name difference between the design example and the bilingual user guides
  was accepted because both examples use the runtime object consistently within their own section.
- Kimi's independent review found the zero-time sample pair (F1/F9); monotonic timestamps and the
  interval gate now prevent a long-interval immediate close from reporting evidence.
- F10 is addressed by the 10 millisecond lower bound and a daemon fixed-delay scheduler separate
  from deadline detection; the scheduler is retained and shut down with the other framework
  services.
- F11 is addressed by sticky first-failure recording, `SAMPLING_FAILED`, and normal report
  publication after runtime sampling errors; sampling `Error` values are rethrown after publication.
- F15 is addressed by updating runtime Javadocs and both user guides for observation retention,
  `inFlight()`, `snapshot()`, and `awaitQuiescence()`.
- F2/F3/F4/F6/F7/F8 remain counter-signal boundaries and are documented as such; sticky evidence is
  intentional window-level history, not a close-time health assertion.
- F5/F12/F14/F18 are addressed by the precise interval accessor, the requested interval in the
  report, corrected closing-state Javadoc, imported exception types, Guava invariant validation,
  and diagnostic-label documentation. Equality/hash-code and structured label APIs are deferred
  because this change does not need a new report value model.
- F13 is documented as an unbounded counter-read limit; F17's automatic closing and convenience
  APIs are not added, preserving explicit lifetime ownership. The close warning addresses F7's
  visibility concern without imposing a new timeout policy.
- A separate read-only adversarial review checked interleavings, every changed contract clause,
  test quality, Java 8, and generics. It found missing migration entries and a missing two-failure
  assertion; both v0.4 migration guides and the scheduled-runtime-failure/final-Error test now cover
  them. Its follow-up accepted both fixes and found no new implementation defect.
- Follow-up review retained the finding that submission-only examples closed before any real
  interval elapsed. The examples now use a bounded window on an independent application control
  thread and state that publication requires close. Request-end accounting and general wait
  interception remain outside this counter-only contract.
- Kimi's follow-up retained the documentation findings about missing queued work, interval-bound
  window duration, early interruption, executor projection terminology, and reading published
  reports after a sampling `Error`. The examples and adjacent guidance now address each point;
  no new execution or wait-registration mechanism is introduced.

## Mutation verification

- The runtime-inclusive PIT run produced complete XML for 176 mutants: 99 killed by tests, 24
  surviving, 46 without coverage, 6 timed out, and 1 run error. Despite Maven's successful exit this
  is not an all-green mutation result; timeouts and the run error are inconclusive.
- Every survivor was classified. Removing the single-winner sampling-waiter invariant check is
  equivalent under the supported close protocol. The other survivors expose gaps in the selected
  tests: runtime logging/service cleanup and unrelated admission paths, scheduled-future retention,
  pre-close status, nonzero completion counters, exact nanosecond equality, resetting separate
  stagnant runs, and zero-worker/empty-queue saturation boundaries. Additional assertions directly
  cover retained sampler cancellation, pre-close status, and nonzero completed counts; isolated
  reversions make each fail. Remaining survivors are acknowledged test gaps, not equivalents.
- A subsequent run scoped to the report and the executor/sample predicates completed 37 mutants:
  33 killed, 4 surviving, no uncovered mutants, no timeouts, and no run errors. Its four survivors
  are the exact elapsed-time boundary, the separate-run interval reset, and the zero-worker and
  empty-queue saturation boundaries described above. The report itself has no surviving mutant in
  this run. This narrower result does not replace the runtime-inclusive run's limitations.
- The follow-up adds deterministic samples around the exact elapsed-time boundary, separates
  stagnant runs with progress and an empty queue, and checks zero-worker and empty-queue states.
  Isolated reversions of each corresponding predicate or reset make its regression test fail;
  restoring the predicates preserves the original algorithm. A fresh run scoped to the report
  and executor/sample predicates completed 38 mutants, all killed, with no uncovered, surviving,
  timed-out, or run-error outcomes. This closes the four predicate gaps in the earlier focused
  result, not the unrelated runtime coverage gaps from the broad run.

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
  rerun), and `stored.get() < CAPACITY` changed to `<=`, equivalent at steady state because the
  mutant's extra insertion at capacity is rolled back into the same suppressed outcome. The remaining
  survivors and uncovered branches are those classified above; `Key.equals` returning true for a
  non-`Key` argument is unreachable because the map compares only keys.
