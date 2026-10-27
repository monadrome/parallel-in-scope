# ADR 0004: Lifecycle Blocking Queue Recovery Contract

- Status: Superseded
- Date: 2026-08-22
- Decision scope: `ClosableBlockingQueue` shutdown and recovery semantics
- Supersedes: None
- Superseded by: [Draining close contract](../design/draining-queue-contract.md)

> This ADR recorded the abrupt-close recovery model (`OPEN -> CLOSING -> CLOSED` with
> `remainingList()`). That model was removed and replaced by the draining-close model
> (`OPEN -> DRAINING -> DRAINED`, no recovery channel) of `DrainingBlockingQueue`, recorded in
> the draining close contract. Isolating queued elements at shutdown made the query channel
> report an empty queue that still held data, and that paradox is what required a recovery
> channel; draining keeps queued elements consumable until the queue is empty, so no recovery
> channel is needed. Kept for history; do not treat it as the current shutdown contract.

## Context

The queue must release blocked producers and consumers during shutdown while preserving elements
that were already linked at the shutdown linearization point. Recovery must be deterministic,
bounded, and safe when multiple consumers drain it concurrently. The implementation also targets
Java 8 bytecode while exposing the backed reverse-view behavior available on newer JDKs.

## Decision

Use a one-way `OPEN -> CLOSING -> CLOSED` lifecycle, detach queued elements into a recovery list at
shutdown, and keep `drainTo(Collection)` available after shutdown to claim recovery elements in FIFO
order. `remainingList()` remains the post-termination inspection API. Poison signalling is virtual and
identity-based; it is never stored in the queue or recovery list.

## Alternatives Considered

- Reject every collection operation after shutdown and expose recovery only through
  `remainingList()`. Rejected because standard `drainTo` is the natural transfer API and recovery
  callers may need bounded incremental ownership.
- Store poison objects as queue nodes. Rejected because one node cannot release an unbounded number
  of blocked consumers and would corrupt capacity/iteration semantics.
- Keep a thread registry to interrupt blocked callers. Rejected because lifecycle guards and monitor
  admission provide release without interrupting user threads.

## Consequences

Shutdown recovery has an explicit ownership-transfer operation and concurrent drains cannot duplicate
elements. `remainingList()` is empty after successful drains, while target collection failures do
not restore already claimed elements. The queue has more lifecycle-specific code and callers must
distinguish recovery transfer (`drainTo`) from closed consumer operations (`take`/`poll`).

## History

Condensed on 2026-10-27; the previous full text is
`git show 1eeaaf0:adr/0004-lifecycle-blocking-queue-recovery.md`. Removed: the appended
`LifecycleQueueV2` contract (requirement sources, constructor rules, lifecycle and iterator
contract, sequenced reverse view, acceptance checks), which described the removed implementation.
