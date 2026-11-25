# ADR 0016: Remove `TaskType` and workload-fit screening

- Status: Accepted
- Date: 2026-11-24
- Decision scope: whether tasks and entries carry a workload declaration (`TaskType`), the
  submission-time comparison of the two declarations, and the runtime low-CPU screen; which of
  their enqueue-refusal and log-delivery rules remain
- Stability: beta
- Category: core
- Supersedes: [ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md) and
  [ADR 0010](0010-always-on-workload-fit-screening.md)

## Context

ADR 0008 made `TaskType` an optional diagnostic declaration on the task side
(`BatchOptions.taskType`, `TaskOptions.taskType`) and the entry side
(`ParRuntime.Builder.register(ParId, ExecutorService, TaskType)`), with two consumers: a
submission-time comparison of the two declarations and a runtime low-CPU screen. ADR 0010 made the
screen always on and judged it per entry. In the 2026-11 API-reduction review the maintainer judged
the CPU-utilization screen unnecessary and asked to remove it together with everything that existed
only to serve it.

- **The screen is a heuristic with a cost on the execution path.** Every execution admitted through
  a `COMPUTE` entry or unit read thread CPU time through `ThreadMXBean` and wall time before and
  after its own code, then committed two totals, all inside the execution kernel's task boundary.
  GC, safepoints, and CPU-quota throttling lower the ratio and spinning raises it; ADR 0010 left the
  epoch length and threshold uncalibrated against real load; and the screen is unavailable when the
  JVM does not support or enable thread CPU timing. The screen also needed its own rules for
  dropping its failures, `Error` included, so that a diagnostic could not replace a task's result.
- **Without the screen, the declaration has one narrow consumer.** The submission-time comparison
  logs only when an entry and a unit both declare a type and the types differ. That finds a task
  whose author knew it blocks, declared it, and wired it to the wrong pool. The incident ADR 0008
  was written for, a task whose author does not know it blocks (hidden JDBC, synchronous logging, a
  later-added RPC), carries no declaration, so the comparison never sees it. The remaining surface
  (`Par.taskType()`, the option accessors, and the type carried through the unit spec, task node,
  and multi-task context) existed only to feed the two consumers, and the multi-task context held
  its `Par` only so that the screen could attribute measurements to an entry.

## Decision

1. Remove `TaskType`, the `taskType` wither and accessor on `BatchOptions` and `TaskOptions`,
   `ParRuntime.Builder.register(ParId, ExecutorService, TaskType)`, `Par.taskType()`, the
   submission-time comparison and its bounded dedup table, `ParRuntime.workloadFitReport()` with
   `WorkloadFitReport`, and the screen's timing in the task boundary. No replacement API is added
   and nothing is deprecated first. Code using them stops compiling; no execution behavior changes,
   because neither consumer ever changed admission, queueing, rejection, cancellation, deadlines,
   or outcomes.
2. Enqueue refusal stays expressed only by `rejectEnqueue`, which `SubmissionAwareBlockingQueue`
   reads, and the once-per-`Par` warning that a requested refusal is inert stays.
3. Admission diagnostics stay delivered on a runtime-owned thread, never on the admission thread.
   The executor wait graph's cycle warnings, the registration warnings, and the inert-refusal
   warning keep using that path.
4. Pool-purpose checks belong to the application: a service that needs an IO pool can accept only
   an application type wrapping that pool's `Par`, so wiring the wrong pool fails to compile.
   Finding blocking calls on compute threads belongs to tools that observe the calls themselves,
   such as a blocking-call detector agent (for example Reactor BlockHound) enabled in tests or
   staging.

## Alternatives Considered

- **Remove only the screen and keep `TaskType` with the submission-time comparison.** Rejected: the
  declaration would again be metadata that almost nothing reads, the first problem ADR 0008 named,
  and its one consumer catches only a deliberate declaration wired to the wrong pool, which an
  application type catches at compile time instead of in a log.
- **Refuse a `BLOCKING` unit on a `COMPUTE` entry.** Rejected: it still misses the undeclared
  blocking task. `BLOCKING` also covers waiting for nested batches, so a compute task that honestly
  declares its nested `Par.map` would be refused, which pushes users to stop declaring or to
  mislabel. A workload label that changes behavior is what the project removed twice before: inline
  execution on rejection for `CPU_BOUND` before v0.3, and enqueue refusal for `CPU_BOUND` before
  v0.4. A unit's type is known only at submission, so a wrong label would surface as a failed
  production request instead of a log line.
- **Keep the screen but make it opt-in or sampled.** Rejected: ADR 0010 already rejected both shapes
  for their own reasons (an opt-in screen depends on someone remembering to enable it; sampling adds
  random attribution error), and either shape keeps the `ThreadMXBean` dependency and the timing
  hook in the task boundary for a signal the maintainer judged unnecessary.
- **Deprecate in 0.5 and remove later.** Rejected: public APIs may break between `0.x` releases with
  documented rationale, the removal surfaces as a compile error rather than a silent behavior
  change, and the migration only deletes calls.

## Consequences

Positive:

- The public surface loses `TaskType`, `WorkloadFitReport` and its two nested measurement types, a
  `register` overload, `ParRuntime.workloadFitReport()`, `Par.taskType()`, and the `taskType`
  wither and accessor on both option types.
- The task boundary runs user code without timing it, so executions admitted through former
  `COMPUTE` entries no longer pay clock reads or atomic updates.
- No execution behavior changes.

Costs and risks:

- Breaking change for 0.4 users: `.taskType(...)` calls, the three-argument `register`, and reads of
  `workloadFitReport()` or the `taskType()` accessors stop compiling. The migration is in the
  changelog.
- A blocking task inside a compute pool again has no library signal. The executor wait graph still
  records synchronous waits between registered pools, and starvation observation still samples
  saturation without progress, but neither sees a blocking call that is not a library wait.
- Users who relied on the mismatch warnings lose them and must express pool purpose in their own
  types.

## Reconsider When

- a signal for blocking inside a pool becomes available on the supported JVMs that is cheap enough
  to leave on and reliable without per-environment calibration;
- users report blocking-in-compute-pool incidents that the executor wait graph, starvation
  observation, application types, and blocking-call detectors all miss.
