# Diagnostics

The runtime ships four diagnostics — the executor wait report, workload-fit screening fed by
optional task-type declarations, and the starvation observation scope — plus per-task telemetry
frozen into results. They only observe; they never change admission, queueing, rejection,
cancellation, deadlines, or outcomes.

## Declare task types {#task-type}

`TaskType` is an optional workload declaration on both sides: an entry declares the work it is meant
to carry at registration, and a batch or task declares what its execution does with
`BatchOptions.taskType` or `TaskOptions.taskType`. The values are `COMPUTE`, `BLOCKING` (any wait:
I/O, locks, sleeping, joining, or waiting for nested work), and the default `UNKNOWN`. A nested batch
and the terminal combine of `mapAndCombine` do not inherit the parent's type.

The declaration never changes admission, queueing, rejection, cancellation, deadlines, or outcomes.
It feeds two diagnostics:

- **Submission-time comparison.** When an entry and a unit both declare a type and they differ, the
  library logs it once per entry and unit name: `WARNING` for a `BLOCKING` unit on a `COMPUTE` entry,
  `INFO` for the reverse. The message only says that the declarations disagree, and it is logged from
  a runtime-owned thread, so a log handler that throws or blocks never affects the submission; messages
  are delivered independently and may appear out of submission order. The runtime remembers
  at most 1024 such pairs; after that it logs one summary and no further mismatch of a new pair. Use
  fixed unit names, not names built per call.
- **Low-CPU screening.** The runtime always compares the thread CPU time of executions admitted through a
  `COMPUTE` entry or unit with their execution time; see [Screen workload fit](#workload-fit). This
  catches blocking work its author did not know about, which a declaration alone cannot.

## Observe nested work

Every `ParRuntime` records, always and without a scope, which of its registered executors synchronously wait for which. A task execution that calls `map`, `mapAndCombine`, or `runAll` waits for every unit it admits; when the awaited unit runs on a starvation-prone pool, that is a wait from the execution's pool to that pool. A combine only consumes its inputs, so it adds no wait of its own, and a top-level call has no waiting pool. When the recorded waits first form a cycle — a pool waiting on itself included — the runtime logs one JUL `WARNING` from a runtime-owned thread, never from the submitting one.

Read the current state at any time, for example at the end of a test:

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
```

`cyclicComponents()` lists each cyclic strongly connected component: its `members()`, one concrete `cycle()` in wait order, and in `toString()` the first pair of task units observed behind each wait. Each executor appears as the set of every `ParId` it is registered under — two ids naming one pool are one executor, and a name alone never makes a cycle.

The report accumulates for the runtime's lifetime, so it is a structural risk signal, not a deadlock proof: a cycle may join waits from unrelated requests that never overlapped — which is exactly how two requests on two bounded pools starve each other — and a pool with headroom may never starve. An empty report says nothing about external futures not adopted with a producer `Par`, direct executor submissions, locks, opaque or unregistered executors, or other runtimes. A wait is attributed to the executor its unit was submitted to; when `CallerRunsPolicy` runs an execution inline, the blocked thread may belong to another pool. The warning is best effort — a failing logging handler may drop it — while the report is always available. For a verdict on one piece of code, run it against a dedicated `ParRuntime`.

## Screen workload fit {#workload-fit}

An execution that blocks inside a compute pool — hidden JDBC, synchronous logging, a later-added RPC —
holds one of few threads while using little CPU. Declare the pool's purpose and the runtime screens
it all the time; no scope has to be opened:

```java
ParRuntime global = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)
        .build();

WorkloadFitReport report = global.workloadFitReport();
for (WorkloadFitReport.ParMeasurement par : report.pars()) {
    if (par.issue()) {
        log.warn("Low CPU use on a compute pool: {}", par);
    }
    for (WorkloadFitReport.UnitMeasurement unit : par.units()) {
        log.info("{} on {}: CPU/execution {}", unit.unit(), par.par(), unit.cpuToExecutionRatio());
    }
}
```

The runtime times only the executions whose entry or unit is declared `COMPUTE`, comparing each execution's
thread CPU time with its execution time (time running, not queued). It judges every entry's totals in
epochs of ten seconds of execution time: the first epoch whose CPU time is below half its execution
time sets `issue()` for the runtime's lifetime and logs one `WARNING` from a runtime-owned thread.
Judging the whole entry catches blocking spread over many small units, and judging per epoch keeps a
regression after a long healthy period from being diluted. `units()` attributes the totals to up to
256 units per entry, a unit being a name with its declared type; executions of further units count only
in `unattributedExecutions()`. A unit
declared `COMPUTE` is judged the same way for `suspectedDeclarationDrift()`, logged once at `INFO`;
drift never sets `issue()`.

Counts are cumulative and never decrease, so the difference between two reports measures the
interval between them. A `BLOCKING` unit on a `COMPUTE` entry is timed and counts toward its entry;
an execution whose entry and unit are both undeclared or `BLOCKING` is never timed and pays nothing. Work
submitted directly with `execute` is never measured, and an entry absent from `pars()` was not
screened. When thread CPU time is unsupported, disabled, or unreadable, screened executions count in
`unmeasuredExecutions()` and one `WARNING` says so; the library never enables CPU timing itself. A
screened execution pays two thread-CPU and two wall-clock reads plus an atomic update of its unit's and its
entry's totals.

### Limits {#workload-fit-limits}

- Low CPU use is a screening lead, not proof of blocking: GC, safepoints, CPU quota throttling, and
  more runnable threads than cores lower the ratio, and spin-waiting raises it. The epoch length and
  the threshold are internal calibration values, not a stable contract.
- An execution that waits for a nested batch holds its own thread while waiting, which says nothing about
  the nested batch's pool.
- Compute work in a blocking pool is found only by the submission-time comparison, when both sides
  are declared.

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
All observations contain identity, submit/start/end times, duration, outcome, failure, and actual
successful values. Never-started tasks have zero start/end times. Missing observations stay missing
in the frozen result; no pending business future is returned. Batch and group
`terminationKindCounts()`/`reportString()` still include every terminal task, regardless of missing observations.

`failure()` carries the detail, not the verdict. A successful task always reports null. Every other
outcome carries a throwable: the execution-thrown exception for `USER_FAILURE` — including an
`InterruptedException` or `CancellationException` the execution raised itself — and, for
cancellation-attributed endings such as `TIMEOUT` and `FAIL_FAST`, normally a
`LeanCancellationException` naming the outcome. Read `successful()` or `outcome()` to classify the
task.
