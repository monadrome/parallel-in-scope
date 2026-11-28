# Diagnostics

The runtime ships one diagnostic, the executor wait report, plus per-task telemetry frozen into
results. Both only observe; they never change admission, queueing, rejection, cancellation,
deadlines, or outcomes.

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

Work submitted directly to a pool, such as `pool.execute(handler)`, records no wait, so this report
cannot see it. Watch such pools with your metrics stack's executor gauges (pool size, active
workers, queue depth, completed tasks): a full pool with a growing queue and no completions is
the symptom to alert on.

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
