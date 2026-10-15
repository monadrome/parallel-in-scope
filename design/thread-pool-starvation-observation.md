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
task count, and the number of no-progress samples supporting the signal.

## Contract

1. The observation MUST be opt-in. Creating a `ParRuntime` or using existing `Par`, `TaskGroup`,
   and `TaskGraphObservationScope` APIs MUST NOT start sampling.
2. The observation MUST NOT wrap, submit, cancel, interrupt, reorder, or otherwise alter user
   tasks. The registered executors remain borrowed resources owned by the application.
3. Each distinct physical executor visible through the registered runtime entry MUST be sampled at
   the requested positive interval. Multiple `ParId` entries for one physical executor MUST share
   one observation and render their labels together.
4. A visible executor MAY produce `POTENTIAL_STARVATION` only after consecutive samples show a
   positive pool size, every current worker active, queued work present, and no increase in the
   executor's completed-task count.
5. `POTENTIAL_STARVATION` MUST remain a risk signal. It MUST NOT claim a dependency cycle, prove
   that a worker is blocked, or change task outcomes.
6. A registered executor that cannot be inspected as a `ThreadPoolExecutor` MUST be named in
   `unsupportedExecutors()` and, when no visible pool reports evidence, the status MUST be
   `INSUFFICIENT_VISIBILITY`. `NO_ISSUE` MUST mean all registered executors were visible and no
   evidence was captured.
7. The report future MUST be read-only, publish exactly once when `close()` normally returns, and
   remain available after close. Concurrent close calls MUST not publish a second report.
8. Closing the runtime MUST wait to retire its diagnostic scheduler until open starvation
   observations have published. Closing an observation MUST release that retention. The observation
   MUST NOT make the runtime close a registered executor.
9. Sampling and report publication MUST use Java 8 APIs and MUST preserve caller interruption
   state when a concurrent close waits for publication.

## Limits and rejected alternatives

The signal intentionally uses executor counters rather than thread-stack inspection. Java 8 does
not expose a stable mapping from `ThreadPoolExecutor` workers to arbitrary application wait
objects, and stack heuristics would produce version- and framework-specific false negatives. A
long CPU-bound task can therefore look like starvation; consumers must use the evidence with their
own task and latency telemetry.

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
- The first review flagged short timing windows in the regression tests. The starvation test keeps
  its positive case deterministic through queue and worker gates, and its settling assertion now
  allows a longer scheduler delay; the progress case uses a longer interval so it releases before
  the first diagnostic sample.
- A cosmetic variable-name difference between the design example and the bilingual user guides
  was accepted because both examples use the runtime object consistently within their own section.

## Mutation verification

- The report-only PIT target completed with 10 mutants: 6 killed, 0 timed out, 0 survivors, and 4
  no-coverage cases from factory branches outside the selected test's execution shape. The latter
  are coverage gaps, not equivalent surviving behavior changes.
- The observation-scope PIT target timed out in its first minion while mutating close/wait paths;
  the runtime-inclusive target timed out as well. Neither timed run is reported as a pass. The
  complete behavioral suite and the deterministic scope regressions remain the acceptance evidence
  for those paths.
