# ADR 0010: Workload-fit screening becomes always-on at runtime level, judged per entry

- Status: Superseded
- Date: 2026-10-30
- Decision scope: the lifecycle, judgment granularity, and report shape of the `TaskType` runtime
  consumer; the observable surface of the submission-time comparison dedup table
- Stability: stable
- Category: core
- Supersedes: "the runtime low-CPU screen reuses the pool starvation observation window" in
  [ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md) decision 4, and "two extra thread CPU
  time reads per execution within the observation window" in its consequences
- Superseded by: [ADR 0016](0016-remove-tasktype-and-workload-fit-screening.md) (the runtime screen removed)

> Note (2026-11-24): superseded by [ADR 0016](0016-remove-tasktype-and-workload-fit-screening.md), which removed the
> runtime screen, `WorkloadFitReport`, and `TaskType`. The workload-fit screening contract was
> retired with them; this record explains history, not current behavior.

## Context

ADR 0008 made the runtime low-CPU screen reuse the pool starvation observation window. Reviewed
against `design/first-principles.md`, that shape has five problems:

- **It depends on remembering to open the window.** Axiom 2 says the way users err is
  "forgetting". The code the screen exists to catch is exactly the code nobody suspects of
  blocking, and that is the code nobody will open a window to inspect. On the same 0.4 development
  line, the executor wait graph had just changed from a request-scoped scope to always-on at
  runtime level for the same reason.
- **Judgment lands on the unit.** Sample thresholds and judgment were computed per "entry + unit
  name + unit type", yet the victim is the pool. When blocking is spread over many small units, no
  unit reaches the sample threshold: a probe ran 30 units of 10 executions each on one `COMPUTE`
  entry, the pool-wide CPU/execution-time ratio was 0.1, and the report still said `NO_ISSUE`.
- **Two orthogonal signals share one state.** With starvation and workload fit merged into one
  `Status`, a priority had to be invented; a runtime that never declared `TaskType` and cares only
  about starvation went from `NO_ISSUE` to `INSUFFICIENT_VISIBILITY` when CPU time was unreadable
  (reproduced by the probe). A wording contradiction in review also came from this priority.
- **Window boundaries bring a whole mechanism.** Sealing, cross-window exclusion, and multi-window
  overlap exist only for the window; per-unit-name accumulation within a window had no cap
  (BACKLOG O-17).
- **Diagnostics of the diagnostics.** `ParRuntimeSnapshot.suppressedTaskTypeMismatches()` only
  grows when unit names are concatenated per call and the dedup table fills up, and by the time it
  fills a summary warning has already been logged; for it, the snapshot grew from "drain state" to
  "drain state plus diagnostic counters".

## Decision

The runtime low-CPU screen separates from pool starvation observation and becomes always-on at
runtime level, judged per entry.

1. Only library-admitted executions whose entry or unit declares `COMPUTE` are timed; other
   executions read no clock.
2. Aggregate per entry, with every 10 seconds of execution time as one epoch: an epoch whose CPU
   time is below half of its execution time makes that entry's issue true for the runtime's
   lifetime, and logs one `WARNING` on the runtime's own thread. Judging per epoch, a regression
   after long health is not diluted by history.
3. Units are used only for attribution and drift: each entry attributes at most 256 "unit name +
   unit type" pairs; other executions count only toward the entry's unattributed total. Units
   declared `COMPUTE` are judged for drift over the same epochs, logging one `INFO`; drift never
   sets an entry's issue.
4. The report is read via `ParRuntime.workloadFitReport()`; counters only increase; subtracting two
   readings gives the measurement of an interval.
5. Pool starvation observation and its report return to their pre-`TaskType` shape, carrying no
   workload-fit measurement or state.
6. The submission-time comparison keeps dedup by `(Par, unit name)`, the 1024-pair cap, and one
   summary warning; the count of suppressed warnings and its snapshot accessor are deleted.

Thresholds and epoch length are internal calibration values; the mechanism and contract clauses
lived in the workload-fit screening contract, retired on 2026-11-24.

## Alternatives Considered

- **Keep the opt-in window.** Rejected: the "depends on remembering" problem, the state coupling,
  and the window mechanism above all remain.
- **Time every execution.** Rejected: undeclared code yields no judgment and the raw numbers have
  no consumer, yet every execution pays clock reads; "declaring enables screening" confines the
  cost to where someone wants a judgment.
- **Sample by ratio.** Rejected: introduces random error into the attribution of low-traffic
  units, and timing only declared entries already bounds the cost.
- **Judge by the cumulative ratio over the runtime lifetime.** Rejected: long health dilutes later
  regression.
- **Judge by a rolling time window.** Rejected: needs a timer or decay state; epochs advanced by
  execution time are driven by the submissions themselves.
- **Keep judging per unit.** Rejected: blocking spread over small units escapes.
- **Warn only once per Par on mismatch.** Rejected: an early, tolerated mismatch would hide a unit
  that is genuinely wired wrong later.

## Consequences

Positive:

- Screening no longer depends on anyone remembering to open anything; pool starvation is judged at
  the pool level.
- The starvation report returns to a single signal; O-17 disappears with the window, and memory is
  bounded by the registered topology times the attribution cap of 256.
- Executions whose entry and unit are both undeclared `COMPUTE` pay no screening cost.

Costs and risks:

- An always-on `WARNING` may be noisy in production; epoch length and thresholds are not yet
  calibrated against real load (BACKLOG O-16).
- Each screened execution pays two extra thread CPU reads, two wall-clock reads, and two atomic
  updates.
- Epoch judgment lags by at most 10 seconds of execution time; the issue is sticky, so after
  recovery the current state is visible only by comparing two report readings.
- No more per-unit judgment: mild blocking in one unit, if diluted at entry level, is not judged an
  issue and is visible only in the unit's measurements.

## Reconsider When

- the always-on `WARNING` proves too noisy in common deployments to calibrate with a fixed epoch
  and threshold;
- long-running processes need a "current state" signal beyond the sticky judgment;
- the attribution cap of 256 proves clearly insufficient in real topologies.
