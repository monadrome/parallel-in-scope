# ADR 0003: Event-Coalesced Purge Maintenance

- Status: Superseded
- Date: 2026-07-26
- Decision scope: Cancelled-task purge maintenance protocol
- Supersedes: None
- Superseded by: [Execution future simplification](../design/execution-future-simplification.md)

> Note (2026-10-27): automatic purge was removed from the main line in 0.3.0
> (`9784312`). The library no longer purges a user executor because a task was
> cancelled; physical queue cleanup is the executor owner's job (`ThreadPoolExecutor.purge()`
> at the application's own maintenance boundary). The removed failure surface was the
> coordination this record designed — phase-notification ordering, observer capture and
> release, the cancellation estimate, and maintenance-service shutdown. The implementation,
> configuration, and tests are preserved on `dev/experimental` from baseline `3557bff`.
> The record below is kept as the design to start from if automatic purge returns.

## Context

Cancelled Futures can remain as references in a `ThreadPoolExecutor` work queue
until the executor consumes them or `purge()` removes them. The library can
observe a queue-relevant cancellation only when `ExecutionPhaseHintFuture`
reports `CANCELLED_BEFORE_RUN`; cancellation exceptions, batch reports, and placeholder
Futures do not prove queue membership. `purge()` must remain the cleanup
operation, and it cannot stop a task that is already running.

The cancellation callback is on a latency-sensitive path. It must not scan a
queue for every cancellation, and concurrent cancellations must not erase one
another or submit unbounded maintenance work. Queue size and capacity are
concurrent advisory snapshots, not an accounting transaction.

## Decision

Automatic cleanup is implemented by `HeuristicPurger` for directly registered
executors whose queue is a `SmartBlockingQueue`. The callback is bound to the
actual executor at task submission; no global Future-to-executor registry is
used.

Each executor owns an independent `IDLE -> SUBMITTED -> RUNNING -> IDLE` state
machine carrying a monotonic `issuedSequence` and a monotonic `settledThrough`
high-water mark:

- A cancellation increments `issuedSequence` once. While maintenance is
  `SUBMITTED` or `RUNNING`, the callback returns without reading the queue,
  evaluating thresholds, logging, or submitting work; the increment alone keeps
  the signal for the next round.
- When idle, the callback qualifies maintenance only when both advisory ratios
  over one snapshot hold: queue pressure `Q / K >= capacityPressureThreshold` and
  cancelled ratio `min(G, Q) / K >= cancelledRatioThreshold` (`Q` queue size,
  `K` capacity, `G` unsettled cancellation estimate).
- The first qualifying signal CASes `IDLE` to `SUBMITTED` and schedules one task
  after a fixed 50ms coalescing delay. The delay is fixed, not sliding, so
  continuous cancellation cannot starve purge.
- The task captures an `issuedSequence` boundary, rechecks enablement and both
  thresholds, calls `ThreadPoolExecutor.purge()`, and advances `settledThrough`
  only through the captured boundary, so signals issued during the scan remain.
- After every attempt the state returns to `IDLE` and evaluates once; new
  qualifying demand submits exactly one next task. A purge exception keeps the
  demand unsettled and allows one delayed retry per sequence; a scheduling
  rejection returns to `IDLE` without changing Future cancellation semantics.
- The estimate expires lazily and never resets a shared counter to zero; a
  disable/enable cycle starts a new generation so stale signals cannot qualify.

Automatic purge is disabled by default, with atomic runtime settings for
enablement and both thresholds. Callback fast paths do not log.

## Alternatives Considered

- **Strict zero-write callback while active:** rejected because it loses the
  cancellation sequence needed to schedule a follow-up purge after the scan.
- **Sliding quiet-period debounce:** rejected because continuous cancellation
  can postpone maintenance indefinitely.
- **Resetting a cancellation counter to zero:** rejected because concurrent
  increments can be overwritten; settlement must advance monotonically.
- **Periodic full scans:** rejected because they create background work when no
  cancellation occurs and still do not provide bounded response to bursts.
- **Queue-private batch deletion:** rejected because the cleanup contract is to
  call `ThreadPoolExecutor.purge()`.
- **Batch-report or cancellation-exception counts:** rejected because they
  include cancellation causes and placeholders that never entered the executor
  queue.

## Consequences

The cancellation path is constant-time in the common case and active callbacks
are isolated from queue contention. At most one purge task per executor is
submitted at a time, and cancellation after the scan is not discarded. Two
thresholds let deployments tune cleanup without a third strength parameter.

The estimate remains advisory and may over- or under-estimate queue garbage
when workers dequeue tasks or producers change capacity concurrently.
`ThreadPoolExecutor.purge()` and the queue iterator's deletion cost still
determine maintenance latency. The design removes stale references; it does not
reclaim running tasks or govern permanently blocked workers.

## History

Condensed on 2026-10-27; the previous full text is
[archive/0003-event-coalesced-purge-maintenance.md](archive/0003-event-coalesced-purge-maintenance.md).
Removed: the threshold formula block and the lazy-expiry marker layout (kept as one-line
rules above), and the `ParConfig` accessor and JUL level details.
