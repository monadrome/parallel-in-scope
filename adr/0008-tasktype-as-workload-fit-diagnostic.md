# ADR 0008: `TaskType` as a workload-fit diagnostic declaration between task and pool

- Status: Superseded
- Date: 2026-10-27
- Decision scope: the values, default, declaration sites, and consumers of `TaskType`; its
  relationship to enqueue rejection
- Stability: stable
- Category: core
- Supersedes: the "`TaskType` semantics (v0.3)" entry in the [decision-log](../design/decision-log.md)
  (`TaskType.MIXED` retained, never reopened); "may affect queueing and rejection policy" in
  [ADR 0005](0005-globalpar-executor-binding.md) rule 10; the `TaskType` queueing/rejection
  semantics in ADR 0006 (retired) rule 2
- Partially superseded by: [ADR 0010](0010-always-on-workload-fit-screening.md) (the runtime low-CPU
  screen became always-on at runtime level and judged per entry, no longer reusing the pool
  starvation observation window)
- Superseded by: [ADR 0016](0016-remove-tasktype-and-workload-fit-screening.md) (`TaskType`, the submission-time comparison, and the
  runtime screen removed)

> Note (2026-11-24): superseded by [ADR 0016](0016-remove-tasktype-and-workload-fit-screening.md), which removed
> `TaskType`, the pool-side declaration, the submission-time comparison, and the runtime screen.
> The workload-fit screening contract and the `TaskType` clauses of API and options §3.2.1 were
> retired with them. §3.2.1, now
> [Admission diagnostics](../design/task-group-api-and-options.md), still holds the inert-refusal
> warning and the delivery of diagnostic logs; this record explains history, not current behavior.

## Context

`TaskType { IO_BOUND, CPU_BOUND, MIXED }` shipped with v0.2.0, defaulting to `IO_BOUND`. Its only
behavior: `SmartBlockingQueue.offer` rejects enqueueing when `CPU_BOUND || rejectEnqueue`; `MIXED`
behaves the same as `IO_BOUND`. In 2026-09 it was decided to keep it as an "intent declaration" and
never reopen. On re-examination, that retention does not hold up:

- **A declaration with no consumer.** No code reads it except for enqueue rejection, and `MIXED`
  has no behavior at all; a declaration nobody consumes goes unchecked, drifts with the code, and
  becomes metadata that "looks documented but may actually be wrong".
- **A diagnostic label doubling as a behavior switch.** A `CPU_BOUND` added to describe a task's
  nature causes elements to be rejected at `SmartBlockingQueue`. Pick the wrong label once and both
  the documentation and the execution semantics are wrong; users therefore dare not declare
  casually.
- **The default is an assertion made on the user's behalf.** Those who write nothing are recorded
  as `IO_BOUND`; the types printed in task-graph reports are fabricated.
- **The problem it should serve goes undetected.** A blocking task entering a compute pool (threads
  ≈ cores) starves the pool's other work while the CPU sits idle; the most common cause is a
  developer who does not know the task blocks (hidden JDBC, synchronous logging, an RPC added
  later). The library had no signal for this misuse.

Detecting "task does not match pool" needs facts from both sides: the task's nature and the pool's
purpose. Declaring only the task leaves nothing to compare against; relying on declarations alone
cannot catch "didn't know it blocks" — actual behavior must be observed.

## Decision

`TaskType` becomes an **optional diagnostic declaration** shared by the task and pool sides,
affecting no execution behavior, landed in one step.

1. Values are `COMPUTE`, `BLOCKING`, `UNKNOWN`; `IO_BOUND`, `CPU_BOUND`, and `MIXED` are deleted.
   The rename is deliberate, so code relying on the old rejection behavior fails to compile instead
   of silently changing behavior. `BLOCKING` means blocking the thread at any stage (IO, locks,
   sleep, join, waiting on other tasks) and covers the old `MIXED`.
2. Both sides are optional — tasks (batch, group member, combine) and pools (at registration) —
   both default to `UNKNOWN`, and `UNKNOWN` may be written explicitly; nested units do not inherit
   the parent unit's type.
3. `TaskType` MUST NOT change admission, queueing, rejection, cancellation, deadline, attribution,
   or task outcome. Enqueue rejection is expressed only by `rejectEnqueue`.
4. Two consumers:
   - **Submission-time comparison** (always on): when both sides declare and disagree, warn once
     per `(Par, unit name, direction)`; no warning when either side is `UNKNOWN`.
   - **Runtime low-CPU screen**: always on at runtime level; times the library-admitted executions
     whose entry or unit declares `COMPUTE`, judged per entry, with the unit used only for
     attribution and drift ([ADR 0010](0010-always-on-workload-fit-screening.md)). The ratio is
     only a screening clue, not proof of blocking.
5. Diagnostics are only logged or reported and MUST NOT fail tasks.

Mechanism details, thresholds, warning wording, and contract clauses landed in the live contracts
on 2026-10-30: API and options §3.2.1 and the workload-fit screening contract (`proposal:P0025`).
On 2026-11-24 the screening contract and the `TaskType` clauses of §3.2.1 were retired;
§3.2.1 remains live as [Admission diagnostics](../design/task-group-api-and-options.md).

## Alternatives Considered

- **Keep the status quo (three values plus enqueue rejection).** Rejected: none of the four
  problems above is solved, and `MIXED` remains a value with neither behavior nor diagnostics.
- **Delete `TaskType` entirely, keeping only `rejectEnqueue`.** This was the first conclusion at
  re-examination; both independent analysts (Kiro, Kimi) reached it. Rejected: it leaves "blocking
  task in a compute pool" with no declaration to rely on, and the concept cost is lowest when the
  pool-side purpose declaration and the task-side declaration share one enum.
- **Declare on pools only, not on tasks.** Rejected: misses "dependency injection wired to the
  wrong pool" mistakes where both sides are clear and only the wiring is wrong; the task-side
  declaration is optional, so non-users pay nothing.
- **Make both declarations mandatory.** Rejected: the default is not dangerous, it only loses one
  diagnostic; the project mandates explicit choice only when a default is dangerous (as with
  timeout). Mandatory fields only invite careless answers, which are worse than none.
- **Keep the `CPU_BOUND`/`IO_BOUND` names and change only the semantics.** Rejected: code relying
  on the old rejection behavior would silently change behavior.
- **Infer pool purpose from pool structure (threads ≈ cores, `ForkJoinPool`) and warn.** Rejected:
  a core-sized pool is a legitimate way to bound downstream concurrency, and inferred warnings
  would be noisy.
- **Use thread-state sampling as the primary runtime signal.** Rejected: a blocking socket read
  shows as `RUNNABLE` in the JVM, which misses exactly the most common IO blocking.
- **Use CPU saturation to detect compute tasks oversubscribing a blocking pool.** Rejected: CPU
  saturation does not imply too many runnable threads, and on Java 8 `availableProcessors` does not
  reliably reflect container quotas. This direction is covered only by submission-time comparison.
- **Land in two steps (pool side and observation first, task side later).** Rejected: the
  maintainer required one landing; both sides share the same enum, so splitting only adds one more
  migration.

## Consequences

Positive:

- A blocking task entering a compute pool gets a library-internal signal for the first time,
  covering even the "didn't know it blocks" case.
- Declarations gain real consumers; when a declaration disagrees with measurement it is reported as
  suspected drift, so labels no longer go unchecked.
- Declarations decouple from execution semantics, so users can annotate freely; the `UNKNOWN`
  default no longer fabricates facts on the user's behalf.

Costs and risks:

- Breaking change: three constants are deleted, and the `CPU_BOUND` enqueue-rejection semantics
  must be rewritten as `rejectEnqueue(true)`; a migration entry goes into the v0.4 migration guide.
- The runtime screen is heuristic: GC, safepoints, and CPU-quota throttling depress the ratio,
  while spinning inflates it; thresholds need empirical calibration and are not promised stable
  across environments.
- A compute task entering a blocking pool is detected only when both sides declare.
- Executions whose entry or unit declares `COMPUTE` pay two extra thread-CPU-time reads
  ([ADR 0010](0010-always-on-workload-fit-screening.md)); when the JVM does not support or enable
  thread CPU timing, the screen is unavailable.
- Dynamically concatenated unit names make submission-time warnings recorded per name until the
  dedup cap; unit names should be fixed business names.

## Reconsider When

- measurements show the low-CPU screen's false-positive rate in common deployments is too high to
  calibrate with fixed thresholds;
- a runnable-concurrency or context-switch signal reliably available on Java 8 appears, making
  reverse misuse decidable at runtime;
- thread CPU timing becomes generally unavailable or unacceptably expensive on mainstream target
  JVMs.
