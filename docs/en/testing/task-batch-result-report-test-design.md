# TaskBatchResult Report Testing Design

## Goals

The batch result's counting reads must describe completed, failed, cancelled, and timed-out work without depending on a lucky thread schedule. Tests should verify observable state and the public contract, not implementation timing.

## Behaviors under test

- Results preserve the input order where the API promises ordering.
- Successful, failed, cancelled, and timed-out tasks are reported separately.
- A task failure triggers fail-fast cancellation of the remaining batch.
- `outcomeCounts()`, `failedTaskResult()`, and `reportString()` read frozen terminal results and stay stable on repeated reads; they never report RUNNING.
- Empty and failed/cancelled batches have deterministic counts.

## Test layers

### Unit tests

Construct terminal ImmediateResult values directly. This keeps public report assertions deterministic
without starting an executor. Internal live-future tests are a separate layer and do not imply that
the public TaskBatchResult changes after return. Assert exact counts and states instead of checking
that a string merely contains a fragment. `failedTaskResult()` selects the first USER_FAILURE or
SUBMISSION_FAILURE in input order; pure timeout/cancellation leaves it empty.

### Demo integration tests

Use the demo test classes to exercise a real `Par.map` call, batch cancellation, and the report formatting together. Integration tests should validate the public workflow while unit tests own the combinatorial state matrix.

## Avoid timing-based tests

Do not arrange threads with arbitrary `sleep` calls or require a fixed number of tasks to finish before cancellation. Use latches, settable futures, and explicit completion signals. A test that swallows task exceptions just to obtain a convenient count is testing the wrong contract.

## Maintenance rules

When the counting reads change, update the state matrix and the integration scenario together. Keep assertions specific enough to catch a regression but independent of executor thread names and incidental scheduling order.
