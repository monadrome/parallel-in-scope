# Batch Terminal Combine (Par.mapAndCombine) Contract

`Par.mapAndCombine(elements, function, options, combinePar, combiner)`: a bounded homogeneous
batch carrying **one** terminal combine task. The Batch/Group boundary is unchanged — the batch
side is not a role-enumerated superset; the group contract is in
[task-group-terminal-combine.md](task-group-terminal-combine.md). This document constrains only
the batch combine's mechanism invariants.

## Lifecycle invariants

- MUST: the combine task is prepared in the same admission as all elements (token,
  TaskExecutionContext, and TTL capture all complete on the calling thread), and bound before
  submission; no lazy preparation.
- MUST: the combine view wraps the real prepared future from creation and joins the batch
  token's bind set; the batch token MUST NOT commit SUCCESS before the combine is terminal, and
  the fail-fast/deadline cascade MUST be able to reach an unsubmitted combine.
- MUST: the combine is submitted exactly once after all elements succeed (three layers: a
  one-shot latch + a future terminality check + a phase claim), and is submitted to combinePar
  only at that moment; when any element fails, is cancelled, or the deadline expires first, the
  combine does not run and goes terminal as cancelled (following batch attribution), and a
  submission rejection is SUBMISSION_FAILURE.
- MUST: the combiner is a `Combiner<List<@Nullable E>, C>`, receiving element success values in
  input order; successful null elements appear as null entries, and element-level nullability
  must be written on the signature (JSpecify-aware users check for null accordingly); checked
  exceptions record USER_FAILURE with the original cause kept.
- MUST: the combine has its own MultiTaskContext (a structural child node of the batch unit, a
  cancellation child token). Executor waits are installed once by "who waits on whom"
  ([task-graph.md](task-graph.md) §4): the caller waits on the batch, the caller waits on the
  combine; the batch feeding the combine is a data dependency and MUST NOT be recorded as a
  wait — the combine is submitted by the converging thread only after all elements are
  terminal, no element thread blocks waiting for it, and a same-pool combine recorded as a wait
  would be falsely reported as an executor self-loop. The caller's wait on the combine MUST be
  recorded: the caller synchronously waits for the combine, and a combine returning to the
  caller's own starvation-prone pool is a real executor self-loop. MUST NOT fabricate group
  memberships or arbitrary DAGs. The combine's child token gets no independent bind — its
  deadline and cascade are carried by the batch token, from the same source as the rule for
  group members whose deadline equals the group's skipping bind.
- MUST: the sliding-window cap, closeGrace bounded cleanup, and execution-exit tracking cover
  both elements and the combine (tracker slots N+1); the frozen result keeps the elements'
  `TaskBatchResult`, the combine's `ImmediateResult`, the combine's terminal observation, and
  the unexited-execution diagnostics together.
- MUST NOT: empty input (null or empty collection) is not allowed — zero elements have no
  fan-out to combine, rejected consistently with an empty group carrying a combine; use
  `Par.map` for an empty batch.
- MUST NOT: combinePar must not belong to another ParRuntime (rejected at declaration); the
  batch's existing `Par.map` behavior is unchanged.

## Submission and attribution

- The submitting thread is the converging thread (the last element's completion-callback
  thread, or the submitting thread under inline execution); for a ThreadPoolExecutor-backed
  combinePar, the group combine's inline guard is reused (saturated CallerRuns records
  SUBMISSION_FAILURE, and user code does not run on the converging thread).
- A `TimeoutException` from the combine's business code is USER_FAILURE, not framework TIMEOUT
  (bind classifies by event source, see [cancellation-propagation.md](cancellation-propagation.md)
  §1).
- A combine failure does not tamper with element results: element SUCCESS/failure attribution
  and observations are kept as-is.
- MUST: `terminalValueOrThrow()`/`orThrow()` check element failures first, then the combine
  itself (the rules are in [synchronous-scope-exit.md](synchronous-scope-exit.md) §3 aggregate
  reads). With an element failure the combine was merely cascade-cancelled, and reading only
  the combine would report the business root cause as a FAIL_FAST cancellation.

## Verification baseline

`ParMapAndCombineTest`: exactly-once, failure/timeout/rejection paths, null elements, TTL
capture, nested inherited budgets, validation rejections, and 50 race rounds; the external Java
8 consumer covers the public entry. Reverse verification: a variant submitting the combine
unconditionally at admission fails 9 of 10 tests.
