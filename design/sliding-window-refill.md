# Sliding-Window Refill Contract (Event-Driven)

Constrains `SlidingWindowSubmitter`: feeding a batch of prepared elements into the business
executor up to a window width. This document is the authoritative description of the current
mechanism; the "historical lessons" section of [interruption-contract.md](interruption-contract.md)
keeps the history of the old submission-thread defect — for mechanism details this document
wins.

## Mechanism

1. The calling thread claims the initial window (`min(element count, parallelism)`) first,
   hands off one by one, and returns the `TaskBatch`; the caller does not wait for refills.
2. Each submitted element's completion listener, when the element goes terminal, releases its
   own window slot and itself claims the next unsubmitted index (if a slot is still free and
   elements remain). Refill therefore happens on **the thread that brought the element to its
   terminal state**.
3. Within one batch, a non-reentrant claim gate (a CAS boolean) serializes refills: inline
   completion (a direct executor or a saturated `CallerRunsPolicy` running the next execution
   directly on the completing thread) lets the same thread keep claiming inside the gate
   holder's loop instead of recursing.
4. The batch's terminal state is published by a `SettableFuture` (the `submitCanceller`): all
   elements claimed is success, a handoff failure is a shared submission failure, and a
   cancellation callback abandons the remaining unclaimed elements.

## MUST

- MUST complete the initial window's handoffs on the calling thread, with element views and the
  token already bound before the handoffs (bind-before-submit unchanged).
- MUST let each index be claimed by exactly one path: normal refill, cancellation abandonment,
  and handoff-failure abandonment are mutually exclusive.
- MUST make cancellation (cancel) claim all unclaimed indices first: the cancellation path
  itself never submits elements, and a refill mid-handoff disposes of its claimed indices in
  place — abandoning when the future is found terminal before submission, while already
  submitted elements run as usual and present their real results externally (see "Cancellation
  and window consistency").
- MUST maintain the window cap: at any moment the batch's in-flight element count does not
  exceed `parallelism`.
- MUST apply the same shared adjudication as the initial window when a refill handoff fails
  (all subsequent elements `SUBMISSION_FAILURE` with the original cause kept).

## MUST NOT

- MUST NOT keep a resident waiting submission thread per batch; refill must not depend on
  `take()` from a blocking queue.
- MUST NOT put refill on the sole deadline timer thread (handoff may block indirectly).
- MUST NOT replace the business pool with a fixed pool to "throttle"; `execute()` may run user
  executions synchronously through a direct executor or `CallerRunsPolicy`, and nested liveness
  relies on the existing mechanisms.
- MUST NOT let a logging failure change the refill terminal path (quiet logging as usual).

## Resource bound

The framework no longer creates threads for batches waiting to refill: the thread cost drops
from O(number of concurrent bounded-window batches) to O(1) (depending only on the business
executor's own threads). The `SlidingWindowResourceBoundTest` regression locks this bound
through public entries; reverse verification yields 24 `submitRemaining` waiting threads on the
old resident-submitter implementation and 0 on the new one.

## Cancellation and window consistency

`cancelDuringHandoffKeepsClaimedElementConsistent` locks: when cancellation lands inside the
refill window, claimed elements execute as usual and present their real results externally,
while unclaimed elements are abandoned as `SUBMISSION_FAILURE`;
`repeatedSubmitAndCancelNeverReportsARanTaskAsUnsubmitted` hammers the same race over many
rounds. That test gates the cancellation point after the claimed element's `execute()` has
returned (handoff complete, execution not yet running); the TOCTOU window between the `settled`
check and `execute()` is outside this case's gating, but behavior in that window follows the
same semantics (claimed elements run as usual).

## Residuals

- Independent review: the R2 rework has not yet obtained the different-model/harness review the
  project requires.
- Each historical (terminal) child batch still accumulates one `ParentLink` node constant on
  the parent token's listener stack — see the residual record in cancellation-propagation §8.
