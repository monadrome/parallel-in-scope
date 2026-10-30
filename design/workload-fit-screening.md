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
   unit totals are updated separately and may differ by bodies that are finishing. Counts MUST be
   cumulative and never decrease.
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

Thread-state sampling was rejected as the primary signal because a blocking socket read is `RUNNABLE`
in the JVM. Inferring a pool's purpose from its structure was rejected because a pool sized to the
core count is also a legitimate limit on downstream concurrency. The opt-in window, measuring every
body, proportional sampling, a lifetime cumulative ratio, time-based rolling windows, and per-unit
verdicts are rejected in ADR 0010. Starvation evidence and workload measurements share no field, so
they are separate reports with separate rules.

## Review history

The first implementation, `d71af957`, screened inside the starvation observation window. Kimi and
Codex reviewed it and its two fix commits read-only in three rounds; the full disposition tables and
mutation records are in `git show e702947c:design/thread-pool-starvation-observation.md`, sections
"Workload-fit review disposition" and "Workload-fit mutation verification".

| Finding | Status after ADR 0010 |
|---|---|
| Round 1, both reviewers: a throwing log handler turned the mismatch warning, and the 0.3 inert `rejectEnqueue` warning, into an admission failure | Holds: all three warnings and the screening warnings are delivered by `Announcements` |
| Round 1, both reviewers: racing first sightings of one pair each claimed a dedup slot | Holds: the memory is one immutable set replaced by compare-and-set |
| Round 2, Codex: a duplicate racing a rollback at the capacity boundary went uncounted | Holds for the memory; the counter it corrupted was removed |
| Round 2, Codex: a failure creating the delivery thread escaped `Announcements.deliver` | Holds, widened below to any `Throwable` from `execute` |
| Round 2, Codex: the off-thread log tests awaited only the first handler | Holds |
| Round 2, Kimi: the overflow summary said mismatches "have been logged" | Holds: it says the memory is full |
| Self-review: an error from the timing skipped restoring the task context | Holds: the timing wraps only the body in `ScopedCallable.callTimed` |
| Round 1, Codex: no deterministic test pinned "a sealed total never changes" | Moot: no window, no seal |
| Round 1, Codex and Kimi; round 2, Kimi: wording of `cpuTimeAvailable()`, `UNKNOWN` raw measurements, `excludedBodies()`, `unlabeledPars()` | Moot: those accessors were removed |
| Round 3, Codex: the `suppressedTaskTypeMismatches()` zero promise | Moot: the counter was removed |
| Self-review: window totals grew without bound per unit name (BACKLOG O-17) | Resolved: no window; at most 256 attributed units per entry |

### Always-on implementation, round 1

Kimi and Codex reviewed the commit that introduced ADR 0010 read-only, in clones without a remote,
each running the full suite (1020/1020) and reading the changed files. Both found the same blocking
defect. The follow-up commit "fix: keep screening failures out of task results" addresses the
retained findings.

| Finding | Disposition | Fix and its verified effect |
|---|---|---|
| Kimi F1, Codex F1 (P0): a `Throwable` from a clock reading escaped the timing; at the end it replaced a returned value or the body's own exception, and at the start it kept the body from running | Retained; reproduced with the scripted clock | `WorkloadFit.start` and `Timing.stop` drop any `Throwable` with the sample; contract item 8 names `Error` and the partial-commit states. Reverting the fix fails all three new timing-error tests |
| Codex F2 (P2): the timing-error test asserted the outcome the contract forbids | Retained | The test asserts `SUCCESS` and the value; a second test keeps the body's own `USER_FAILURE` cause |
| Kimi F2 (P3): the guides, CHANGELOG, ADR 0010, and `ParRuntime` Javadoc said all `BLOCKING` work goes untimed, but a `BLOCKING` unit on a `COMPUTE` entry is timed | Retained | Reworded: a body is untimed when its entry and unit are both undeclared or `BLOCKING` |
| Kimi F3 (P3): the guides counted 256 unit names, but a unit is keyed by name and type | Retained | Reworded in both guides |
| Kimi F4 (P2), noted by Codex: no test raced the last attribution slots or read reports during commits | Retained as a test gap; Kimi's probe passed on the commit | A race of 320 units for 256 slots under a concurrent reader; an off-by-one capacity check and a capacity check outside the retry loop each fail it (the latter 5/5 runs) |
| Self-review: an `OutOfMemoryError` creating the delivery thread escaped `Announcements.deliver`, which caught only `RuntimeException` | Retained; `ThreadPoolExecutor.execute` propagates it from `Thread.start` | Both delivery paths drop any `Throwable`; the tests run admission inside `assertThatCode`, so a reverted fix fails them as assertions, not as a crash of the test JVM |
| Kimi, not new: the always-on epoch and threshold are uncalibrated | Already tracked as BACKLOG O-16 | None |

Mutation coverage after the fixes, on `WorkloadFit*` and `Announcements` with the core mutator set:
82 mutants, 80 killed, 1 timed out (the negated compare-and-set in `Entry.add` loops forever), and 1
equivalent survivor, `executionNanos >= 0` in `lowCpu`: at zero execution time the comparison is
`cpu < -cpu`, false for every CPU delta, which is never negative on one thread.
