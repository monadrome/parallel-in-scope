# Workload-Fit Screening Contract

This contract covers the always-on runtime signal for blocking work that occupies a compute pool.
The runtime times the library-admitted bodies whose entry or unit is declared `TaskType.COMPUTE` and
judges each entry over epochs of execution time. The submission-time comparison of declarations is
in [task-group-api-and-options.md](task-group-api-and-options.md) §3.2.1; the opt-in
[thread-pool starvation observation](thread-pool-starvation-observation.md) is a separate signal that
shares no state with this one. The decision is recorded in
[ADR 0010](../adr/0010-always-on-workload-fit-screening.md), which partially supersedes
[ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md).

## User shape

Before this capability, a body that blocked inside a compute pool produced a library signal only
while someone held a starvation observation open, and the code it screens is the code nobody
suspects. Blocking spread over many small units also escaped, because each unit was judged alone.

After, declaring the pool's purpose once is enough:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)
        .build();
runtime.par(ParId.of("cpu")).map(items, this::transform,
        BatchOptions.timeout("transform", Duration.ofSeconds(5)));
// When bodies wait, one WARNING names Par 'cpu'; the report attributes the time to units.
WorkloadFitReport report = runtime.workloadFitReport();
```

The failure modes removed are blocking in a compute pool that no one looked for, blocking split over
many small units, and starvation-only reports degraded by unreadable CPU time.

## Contract

1. The runtime MUST time only library-admitted bodies whose entry or unit is declared `COMPUTE`.
   Any other body MUST NOT read a clock or touch a total. Work submitted directly to an executor MUST
   NOT be wrapped or measured.
2. A timed body MUST read wall time and thread CPU time before and after its own code, so the CPU
   interval lies inside the wall interval; the timing MUST exclude queueing and the library's cleanup.
3. A measurement MUST be attributed to its runtime and `Par`; entries or runtimes sharing one physical
   pool are measured apart, and nothing MUST be inferred about the whole pool or other submitters.
   Unit totals are keyed by unit name and unit `TaskType`, at most 256 per entry; bodies of further
   units MUST count in the entry's `unattributedBodies()` only.
4. Every total MUST be one immutable value replaced by compare-and-set, so a report reads each total in
   one consistent state and exactly one commit observes a verdict transition. An entry's total and its
   unit totals are updated separately and may differ by bodies that are finishing, and by a sample
   whose bookkeeping failed as item 8 allows. Counts MUST be cumulative and never decrease.
5. An entry's total is judged in epochs of ten seconds of execution time. An epoch ending with CPU time
   below half its execution time MUST set the entry's `issue()` for the runtime's lifetime when the
   entry is declared `COMPUTE`; a unit declared `COMPUTE` is judged the same way for
   `suspectedDeclarationDrift()`, which MUST NOT set `issue()`. The epoch length and the threshold are
   internal calibration values and MUST NOT be documented as stable across GC, hardware, or container
   environments.
6. The first issue of an entry MUST log one `WARNING`, the first drift of a unit one `INFO`, and the
   first unreadable reading of an entry one `WARNING`, each delivered through the runtime-owned
   announcement path of §3.2.1. The text MUST read as a screening lead and MUST NOT claim proven blocking
   or misuse.
7. When thread CPU time is unsupported, disabled, or unreadable, the body MUST count in
   `unmeasuredBodies()` and nowhere else. The library MUST NOT call `setThreadCpuTimeEnabled`.
8. Screening MUST only report. It MUST NOT fail or replace the result of a task, batch, or group or
   keep a body from running: any `Throwable` from the timing or its bookkeeping, an `Error` included,
   MUST be dropped, keeping the body's own result or exception and letting the body's cleanup run.
   Such a failure may lose that body's sample or leave it in its unit's total only, and may lose the
   announcement the sample would have triggered.
9. `ThreadPoolStarvationObservationScope` and `ThreadPoolStarvationReport` MUST NOT carry workload
   measurements or statuses.

## Limits and rejected alternatives

Low CPU use is a screening lead. It can come from waiting on I/O, a lock, or nested work, or from GC,
safepoints, CPU quota throttling, or more runnable threads than cores; spin-waiting raises the ratio.
A body that waits for a nested batch holds its own thread while it waits, which says nothing about the
nested batch's pool; same-pool nested waits remain the executor wait graph's responsibility. Compute
work in a blocking pool is found only by the submission-time comparison when both sides are declared:
CPU saturation of a blocking pool may be a legitimate peak and does not prove too many runnable threads.
A verdict is sticky, so the current state of a recovered entry is read by comparing the cumulative
counts of two reports. Without per-unit verdicts, mild blocking in one unit that the entry's epoch
dilutes is visible only in that unit's numbers.

Propagating an `Error` from the timing was rejected: an `OutOfMemoryError` or `StackOverflowError`
there comes from diagnostic code, the totals are immutable values replaced by compare-and-set, so a
failure mid-commit leaves no torn state, and propagating it would replace the body's own result.
A `ThreadDeath` from `Thread.stop()` that lands inside the timing or a log delivery is dropped with
every other `Throwable`, as the task boundary records one from a body as the task's failure. Rethrowing
it was rejected: a catch clause naming a class makes its declaring class fail to link when that class
is absent, `ThreadDeath` is deprecated for removal, and `Thread.stop()` has thrown
`UnsupportedOperationException` since JDK 20. None of the clock readings, the bookkeeping, `execute`,
or `Handler.publish` declares `InterruptedException`, so a blocking implementation handles an
interrupt itself under the project's interruption rules; one thrown undeclared is dropped like any
other `Throwable`, and in production only a log handler can throw it so, on the runtime's own
delivery thread.

Thread-state sampling was rejected as the primary signal because a blocking socket read is `RUNNABLE`
in the JVM. Inferring a pool's purpose from its structure was rejected because a pool sized to the
core count is also a legitimate limit on downstream concurrency. The opt-in window, measuring every
body, proportional sampling, a lifetime cumulative ratio, time-based rolling windows, and per-unit
verdicts are rejected in ADR 0010. Starvation evidence and workload measurements share no field, so
they are separate reports with separate rules.

## Review history

The first implementation, `d71af957`, screened inside the starvation observation window. Kimi and
Codex reviewed it and its two fix commits read-only in three rounds; the disposition tables and
mutation records are in `git show e702947c:design/thread-pool-starvation-observation.md`, sections
"Workload-fit review disposition" and "Workload-fit mutation verification".

Kimi and Codex reviewed the always-on implementation read-only in two rounds; round 2 confirmed no
new blocking finding. The disposition tables, reverse checks, and mutation records are in
`git show 0c268f32:design/workload-fit-screening.md`, section "Review history". The findings that
changed behavior live in contract item 8 and under Limits. A test interleaving a timing failure with
cancellation was rejected: the timing never inspects the body's exception, so a cancelled body takes
the path the body-failure test already pins.
