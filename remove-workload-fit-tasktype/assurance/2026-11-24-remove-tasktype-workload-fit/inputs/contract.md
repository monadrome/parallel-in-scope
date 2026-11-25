# Frozen contract: remove TaskType and workload-fit screening

Run `2026-11-24-remove-tasktype-workload-fit`. Change: base
`f093bb12f7695db32eaeb1e7e89cba2539944db7` to head `f020503de120d5bcb525d568f90c02f6f8bcbe2c` on the
`dev/v0.5.0` line of `parallel-in-scope` (Java library, package `io.github.monadrome.parallelinscope`).

The change removes the `TaskType` workload declaration, the submission-time comparison of task and
entry declarations, and the runtime low-CPU screen with its report. It keeps enqueue refusal through
`rejectEnqueue`, the warning that a requested refusal is inert, and the delivery of diagnostic logs
on a runtime-owned thread. Contracts below are stated for the public API only.

## Vocabulary

- **Entry (`Par`)**: a logical id registered with `ParRuntime.Builder.register(ParId, ExecutorService)`.
  Two ids may be registered with the same executor object; each id is its own entry.
- **Unit**: one admission through an entry. Public unit kinds: a **batch** (`Par.map`, or the element
  side of `Par.mapAndCombine`, configured by `BatchOptions`); a **group member** added with
  `par(TaskKey, Par, TaskOptions, Callable)` on a group from `ParRuntime.group(...)`; a **group
  combine** added with `combine(...)` or `combineValues(...)` taking a `TaskOptions`. A group member
  or combine is admitted through the `Par` passed with it. An **adopted member** (`adopt(...)`) runs
  no library task.
- **Refusing unit**: a unit whose options set `rejectEnqueue(true)`. The default is `false`.
- **Refusal-capable executor**: a `ThreadPoolExecutor` whose work queue is a
  `SubmissionAwareBlockingQueue`. Any other executor (for example `Executors.newFixedThreadPool`,
  whose queue is a `LinkedBlockingQueue`) is **plain**.

## Observation conventions

- O-1 Diagnostics are `java.util.logging` records published in the logger hierarchy under
  `io.github.monadrome.parallelinscope`. A test observes them by adding its own `Handler` to the
  logger named `io.github.monadrome.parallelinscope` and removing it afterwards; records from child
  loggers reach it through parent handlers. Only level, count, publishing thread, and the entry id in
  the message are contractual; other wording is not.
- O-2 A diagnostic record is published on a runtime-owned thread some time after the admission that
  caused it. Records are delivered independently and may appear out of admission order. A test that
  expects a record waits for it with a bound. No documented point guarantees that every record of a
  runtime has been delivered, so a test asserting that a record is absent observes a bounded window
  and must state that window as an uncertainty.
- O-3 Tests own their executors and runtimes: close every `ParRuntime` (try-with-resources) and shut
  down every executor they create. Waits on futures, latches, and log records are bounded.

## C-1 The workload declaration surface is removed

- Source: ADR 0016 decision 1; CHANGELOG Unreleased entry "Remove `TaskType` and workload-fit
  screening".
- Precondition: the library jar on the test class path.
- MUST NOT: expose a type `io.github.monadrome.parallelinscope.TaskType` or
  `io.github.monadrome.parallelinscope.WorkloadFitReport`; a public method named `taskType` on
  `BatchOptions`, `TaskOptions`, or `Par`; a public method named `workloadFitReport` on `ParRuntime`;
  or a public `ParRuntime.Builder.register` overload that takes a workload declaration.
- Observable: `Class.forName` of a removed type name throws `ClassNotFoundException`; reflection over
  the public methods of the named classes finds no removed method name.
- Uncertainty: this is API-surface evidence, not behavior; no replacement API exists.

## C-2 Enqueue refusal is expressed only by `rejectEnqueue`

- Source: ADR 0016 decision 2; API and options §3.2.1; `SubmissionAwareBlockingQueue`,
  `BatchOptions.rejectEnqueue`, and `TaskOptions.rejectEnqueue` Javadoc; user guide, enqueue refusal.
- Precondition: an entry on a refusal-capable executor whose queue has free capacity and whose every
  worker is busy (for example blocked on a latch that the test releases later).
- MUST: an element of a refusing unit is not queued. Below the pool's maximum size the pool starts a
  new worker for it, so it runs while the busy workers stay blocked. At the maximum size the pool's
  rejection handler receives it; with `ThreadPoolExecutor.AbortPolicy` the element ends `Failed` with
  `FailureCause.SUBMISSION_REJECTED`, its task code never runs, and the unit's result reports that
  failure.
- MUST: an element of a non-refusing unit is queued while the queue has capacity and runs after a
  worker frees up.
- MUST: work handed to the executor by application code outside any unit (for example
  `executor.execute(...)` from the test thread) is queued normally.
- MUST NOT: the terminal combine of `Par.mapAndCombine` request refusal, even when its batch is
  refusing; a combine admitted through a busy refusal-capable executor is queued, not rejected.
- MUST: `BatchOptions.rejectEnqueue()` and `TaskOptions.rejectEnqueue()` return `false` unless set.
- Lifecycle: the test releases every blocker before closing the runtime and shutting down the pool.
- Uncertainty: whether a nested unit started inside a refusing unit's task inherits the refusal is
  not stated; this contract does not cover nested units.

## C-3 An inert refusal is reported once per entry

- Source: ADR 0016 decision 2; API and options §3.2.1; user guide, enqueue refusal; adopted-futures
  contract A7.
- Precondition: an entry on a plain executor.
- MUST: the first refusing unit admitted through the entry causes exactly one `WARNING` record whose
  message starts with `Par '<id>' requested rejectEnqueue`, where `<id>` is the entry's id. Later
  refusing units through the same entry cause no further record.
- MUST: every public unit kind is checked at admission: a batch, a group member, and a group combine
  each cause the record for the entry they are admitted through.
- MUST: the once-only rule is per entry. Two ids registered with the same plain executor each cause
  their own record; a record for one entry never suppresses another's.
- MUST NOT: a non-refusing unit cause the record on any executor; a refusing unit admitted through an
  entry on a refusal-capable executor cause it.
- MUST NOT: the terminal combine of `Par.mapAndCombine` cause the record, because it requests no
  refusal: a refusing batch on a refusal-capable entry combined on a plain entry causes no record; a
  refusing batch on a plain entry combined on a refusal-capable entry causes one record, naming the
  batch's entry.
- MUST NOT: an adopted member undergo admission diagnostics.
- Concurrency: when several refusing units are admitted through one plain entry at the same time, the
  entry still causes exactly one record.
- Uncertainty: the warning names the option and the fix; that wording is not contractual. Whether a
  recognized `TransmittableThreadLocal` executor wrapper around a refusal-capable pool counts as
  refusal-capable is not stated.

## C-4 Diagnostics never change outcomes and never run on the admitting thread

- Source: ADR 0016 decision 3; API and options §3.2.1; `ParRuntime.executorWaitReport` Javadoc;
  executor registration reference (executors the library cannot see through); diagnostics reference,
  "Observe nested work".
- Precondition: a handler under O-1 that records `Thread.currentThread()`, and in some cases throws
  (any `Throwable`) or blocks on a latch when publishing.
- MUST: a unit that causes a diagnostic record completes with the same values and termination as an
  identical non-refusing unit; no exception from logging reaches the caller.
- MUST NOT: the thread that calls `Par.map`, `Par.mapAndCombine`, or `runAll()` invoke the handler for
  that unit's record. A handler that blocks never delays the caller's return; the record is
  published after the handler is released.
- MUST: a handler that throws while publishing changes no outcome; only that record is lost.
- MUST: registration warnings use the same delivery: registering an executor the library cannot see
  through (for example a `ForkJoinPool`, or a Guava `listeningDecorator` around a pool) is accepted
  with one `WARNING` naming the entry id, published on a thread other than the one calling `build()`.
  A throwing handler does not make `build()` fail.
- MUST: when the synchronous waits among registered pools first form a cycle, a pool waiting on
  itself included, the runtime logs one `WARNING` from a runtime-owned thread, never from the
  submitting one. A wait forms when a task execution calls `Par.map`, `Par.mapAndCombine`, or
  `runAll()` for a unit on a starvation-prone pool: a `ThreadPoolExecutor` whose queue buffers work
  behind busy workers (for example `Executors.newFixedThreadPool`). A cached pool, whose queue is a
  zero-capacity handoff, is not starvation-prone. `ParRuntime.executorWaitReport()` reports the cycle
  in `cyclicComponents()` whether or not the warning is delivered.
- Uncertainty: the registration and cycle records' wording is not contractual.

## C-5 Removing the screen changes no execution behavior

- Source: ADR 0016 decision 1 and its consequences ("No execution behavior changes"; executions are
  no longer timed).
- Precondition: entries on refusal-capable `ThreadPoolExecutor`s, default options, no nested waits, no
  open starvation observation.
- MUST: batches and groups whose tasks block (sleep, wait on a latch) or spin complete with their
  values, terminations, and failure attribution unchanged by the kind of work.
- MUST NOT: such runs publish a workload-fit, task-type, or CPU-utilization record. None of the
  retained diagnostics applies to them (an inert refusal needs a refusing unit on a plain entry, a
  registration warning needs an executor the library cannot see through, a cycle warning needs nested
  waits, a starvation report needs an open observation), so they publish no library record at `INFO`
  or above.
- Uncertainty: absence is a bounded-window observation (O-2); "no behavior change" as a whole is not
  decidable, only these observations are.
