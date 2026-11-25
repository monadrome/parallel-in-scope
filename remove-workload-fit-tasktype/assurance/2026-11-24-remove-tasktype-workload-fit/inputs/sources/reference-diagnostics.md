# Diagnostics

The runtime ships two diagnostics — the executor wait report and the starvation observation scope —
plus per-task telemetry frozen into results. They only observe; they never change admission,
queueing, rejection, cancellation, deadlines, or outcomes.

## Observe nested work

Every `ParRuntime` records, always and without a scope, which of its registered executors synchronously wait for which. A task execution that calls `map`, `mapAndCombine`, or `runAll` waits for every unit it admits; when the awaited unit runs on a starvation-prone pool, that is a wait from the execution's pool to that pool. A combine only consumes its inputs, so it adds no wait of its own, and a top-level call has no waiting pool. When the recorded waits first form a cycle — a pool waiting on itself included — the runtime logs one JUL `WARNING` from a runtime-owned thread, never from the submitting one.

Read the current state at any time, for example at the end of a test:

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
```

`cyclicComponents()` lists each cyclic strongly connected component: its `members()` and one concrete `cycle()` in wait order. A component's `toString()` is count-only — member, cycle, and witness counts — so logging a report never expands configured labels; the task units observed behind each wait stay internal to the library. Each executor appears as the set of every `ParId` it is registered under — two ids naming one pool are one executor, and a name alone never makes a cycle.

The report accumulates for the runtime's lifetime, so it is a structural risk signal, not a deadlock proof: a cycle may join waits from unrelated requests that never overlapped — which is exactly how two requests on two bounded pools starve each other — and a pool with headroom may never starve. An empty report says nothing about external futures not adopted with a producer `Par`, direct executor submissions, locks, opaque or unregistered executors, or other runtimes. A wait is attributed to the executor its unit was submitted to; when `CallerRunsPolicy` runs an execution inline, the blocked thread may belong to another pool. The warning is best effort — a failing logging handler may drop it — while the report is always available. For a verdict on one piece of code, run it against a dedicated `ParRuntime`.

## Observe direct executor work

Task-graph observation covers work admitted through this library. Direct submissions to a registered
executor have no dependency edge, so use the explicit starvation observation when an application
also runs unstructured work on that pool. Run this bounded diagnostic window on an application
control thread independent of request workers. The bounded wait belongs to that control thread,
not to a request handler:

```java
ListenableFuture<ThreadPoolStarvationReport> reportFuture;
Duration samplingInterval = Duration.ofSeconds(1);
try (ThreadPoolStarvationObservationScope observation =
        global.openThreadPoolStarvationObservation(samplingInterval)) {
    reportFuture = observation.reportFuture();
    pool.execute(requestHandler);
    submitOtherDirectWork(pool);
    TimeUnit.MILLISECONDS.sleep(samplingInterval.multipliedBy(10).toMillis());
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
if (report.status() == ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION) {
    log.warn("Potential thread-pool starvation: {}", report);
}
```

The scope samples each distinct physical `ThreadPoolExecutor` registered with `global`; it never
wraps, submits, cancels, or interrupts user tasks. Sampling uses a separate daemon scheduler and a
minimum interval of 10 milliseconds. `POTENTIAL_STARVATION` requires a pair of samples at least one
interval apart with every current worker active, queued work present, and no completed-task progress.
Its evidence is a sticky snapshot of a risk seen during the observation window, including
`stagnantIntervals()`; recovery does not clear it. `SAMPLING_FAILED` exposes a sampling exception in
`failureMessage()`, while evidence takes priority if both occur. `NO_ISSUE` means every registered
executor was visible, sampling succeeded, and no signal was captured. A wrapper or foreign executor
appears in `unsupportedExecutors()` and yields `INSUFFICIENT_VISIBILITY` when neither evidence nor a
sampling failure was captured. Keep the scope open for at least two sampling intervals when the
backlog is expected to be diagnosed.
The report also exposes the requested `samplingInterval()` and the count of completed `samples()`.
Evidence contains capture-time counters; sample the pool again to assess its state at close.
The report publishes only when the scope closes; periodic sampling is not a live alert stream.
The example assumes queued submission: `CallerRunsPolicy` can run a handler inline, so keep the
diagnostic control thread separate from submission too in that case. With queued submission the
control thread closes the window even if a handler stalls. Propagate
`InterruptedException` or restore the interrupt flag according to that thread's policy;
try-with-resources still closes the window. Submitting work and immediately closing the scope cannot
diagnose sustained backlog. A request-completion hook alone cannot publish a report for a request
that never returns.
The example spans ten configured intervals. Early or interrupted close can yield `NO_ISSUE` without
a qualifying sample pair; it is not a health assertion. Sampling `Error` is rethrown after report
publication; retain the future before close to inspect the published report in an error handler.

### Limits

- The counters are pool-wide: library tasks and direct `execute` tasks count equally, so a long CPU
  or slow I/O library task can produce `POTENTIAL_STARVATION`.
- `NO_ISSUE` does not rule out deadlock. Empty queues with all workers blocked, `SynchronousQueue`
  or other zero-capacity handoff pools, pools not registered with this runtime, and partial worker
  deadlocks hidden by other completing tasks are outside this signal.
- Direct tasks and library tasks have no dependency edge here. The report cannot say who waits for
  whom, including across pools or runtimes.
- Opening the scope does not add cancellation, deadline, TTL, `SubmissionAwareBlockingQueue`, or
  `rejectEnqueue` semantics to direct tasks. Only TTL wrappers are unwrapped; register the physical
  pool for Guava or custom decorators, or they appear in `unsupportedExecutors()`.
- Forgetting `close()` keeps framework services and sampling alive after `runtime.close()`, makes
  `awaitQuiescence` return false, and keeps `activeObservations()` nonzero. Use try-with-resources or
  one long-lived scope rather than one scope per request; scopes do not auto-close.
- A custom queue whose counter read blocks can also block scope close while it waits for sampling.

## Final observations {#completion-snapshots}

Batch `completions()` is an input-ordered list of available final TaskExitObservation snapshots; a null
entry means final publication was not confirmed. Group `members()` contains available snapshots
keyed by member name, and `terminal()` is the available combine observation or null.
All observations contain identity, submit/start/end times, duration, termination, failure, and
actual successful values. Never-started tasks have zero start/end times. Missing observations stay missing
in the frozen result; no pending business future is returned. Batch and group
`terminationKindCounts()`/`reportString()` still include every terminal task, regardless of missing observations.

`failure()` carries the detail, not the verdict. A successful task always reports null. Every
non-success termination can carry a throwable: the execution-thrown exception for a user-code
failure — including an `InterruptedException` or `CancellationException` the execution raised
itself — and, for cancellation-attributed endings such as `DEADLINE_EXCEEDED` and
`ABORTED_BY_FAIL_FAST`, normally a `LeanCancellationException` naming the reason. Read
`successful()` or `termination()` to classify the task.
