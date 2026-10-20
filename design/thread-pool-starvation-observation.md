# Thread-Pool Starvation Observation Contract

This contract covers the opt-in runtime signal for work submitted directly to a registered
executor. It complements [task-graph.md](task-graph.md): task-graph detection proves a structural
waits-for cycle from library admissions, while this observation covers unstructured submissions
that never create task-graph nodes.

## User shape

Before this capability, direct submissions had no library diagnostic path:

```java
pool.execute(requestHandler);
```

The failure was a queue that stayed non-empty behind all busy workers while the caller only saw a
timeout or stalled request. The same work can now be observed explicitly:

```java
ListenableFuture<ThreadPoolStarvationReport> reportFuture;
try (ThreadPoolStarvationObservationScope observation =
        runtime.openThreadPoolStarvationObservation(Duration.ofSeconds(1))) {
    reportFuture = observation.reportFuture();
    pool.execute(requestHandler);
    // submit other unstructured work
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
```

The report identifies the pool and captures the pool size, active workers, queue depth, completed
task count, and the number of no-progress sample pairs supporting the signal. It also records the
requested sampling interval and any sampling failure.

Earlier development snapshots could report a stalled pool immediately after opening and closing a
scope, without waiting an interval, or silently stop sampling after an exception. The same user code
now requires a real elapsed interval and exposes sampling failures. Migrate development consumers
from `evidence.stagnantSamples()` to `evidence.stagnantIntervals()`, use an interval of at least 10
milliseconds, and handle `SAMPLING_FAILED` with `failureMessage()`. No compatibility alias is retained
for this unreleased API because the old name counted pairs rather than samples.

## Contract

1. The observation MUST be opt-in. Creating a `ParRuntime` or using existing `Par`, `TaskGroup`,
   and `TaskGraphObservationScope` APIs MUST NOT start sampling.
2. The observation MUST NOT wrap, submit, cancel, interrupt, reorder, or otherwise alter user
   tasks. The registered executors remain borrowed resources owned by the application.
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
   `unsupportedExecutors()`. Status priority MUST be evidence (`POTENTIAL_STARVATION`), sampling
   failure (`SAMPLING_FAILED`), unsupported executors (`INSUFFICIENT_VISIBILITY`), then
   `NO_ISSUE`. `NO_ISSUE` MUST mean all registered executors were visible, sampling did not fail,
   and no evidence was captured. A sampling failure MUST be retained in `failureMessage()`; a
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
[extension-and-wrapping.md](extension-and-wrapping.md). Existing task-graph reporting remains the
source of structural cycle evidence.

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
