# Cooperative Cancellation

## Why cooperative cancellation?

Java `Thread.interrupt()` only interrupts blocking operations such as `sleep`, `wait`, and queue operations. A CPU-bound loop can ignore the interrupt flag and continue running. `parallel-in-scope` therefore uses cooperative cancellation: the framework publishes a cancellation signal and task code checks it at explicit checkpoints.

## What the framework does automatically

| Location | Mechanism | Application work |
|---|---|---|
| Before task execution | `ScopedCallable` runs `Checkpoints.checkpoint()` | None |
| During blocking I/O | `futureToken.cancel(true)` interrupts the blocking operation | None |
| Sliding-window submission | `SlidingWindowSubmitter` stops submitting after cancellation | None |

### Sliding-window preparation

Every element has its internal execution future prepared before cancellation and deadlines are
bound. Binding finishes before submission starts; the sliding window only controls when each
prepared task enters the executor. Cancellation therefore reaches running bodies and prevents
unsubmitted tasks from starting. There are no placeholders or later delegate-binding steps.

`Par.map` waits for all results to settle and attempts bounded cleanup before returning frozen
`ImmediateResult` data. `unfinishedBodies()` reports direct bodies whose exit was not confirmed;
terminal cancellation alone does not prove that a body stopped.

Tasks that have not started are skipped, blocked I/O tasks are interrupted, and queued tasks are not submitted.

## Add checkpoints to CPU-bound work

```java
BatchOptions options = BatchOptions.timeout("my-task", Duration.ofSeconds(5)).parallelism(4);

global.par(ParId.of("myExecutor")).map(dataList, item -> {
    for (int i = 0; i < 1_000_000; i++) {
        if (i % 1000 == 0) {
            Checkpoints.checkpoint();
        }
        heavyComputation(item, i);
    }
    return result;
}, options);
```

Prefer the no-argument `Checkpoints.checkpoint()`: it checks the current scope unconditionally and cannot go stale across a rename. The named `checkpoint(taskName, lean)` still validates the current task's name and throws `IllegalStateException` on a mismatch (a typo, a stale name, or a call outside any scoped task) instead of silently skipping the safety check; its `lean=false` form throws the standard `CancellationException` with a stack trace for diagnostics.

The three checkpoint forms differ in what they do **outside** any scoped task:

| Checkpoint | Outside a scoped task | When cancelled |
|---|---|---|
| `Checkpoints.checkpoint()` | Silent no-op | Throws `LeanCancellationException` |
| `Checkpoints.checkpoint(taskName, lean)` | Throws `IllegalStateException` | `lean=true`: `LeanCancellationException`; `lean=false`: `CancellationException` with a stack trace |
| `Checkpoints.rawCheckpoint()` | Works — no scope required; also honors the thread's interrupt flag | Throws `LeanCancellationException` |

Two utilities round out the API:

| Method | Purpose |
|---|---|
| `Checkpoints.sleep(millis)` | Sleep while converting interruption into a cancellation exception |
| `Checkpoints.propagateCancellation(ex)` | Re-throw cancellation exceptions from a catch block |

Add checkpoints at a reasonable granularity: every N iterations of a long loop, between expensive phases, or at the entry to a recursive walk. I/O calls and very short functions normally need no manual checkpoint.

## Do not swallow cancellation

```java
global.par(ParId.of("myExecutor")).map(items, item -> {
    try {
        riskyOperation(item);
    } catch (Exception ex) {
        Checkpoints.propagateCancellation(ex);
        log.error("failed", ex);
        return defaultValue;
    }
}, options);
```

`propagateCancellation` re-throws every `CancellationException`. Any other exception is passed through unchanged — unless the current scope is already cancelled, in which case it throws `LeanCancellationException` instead of returning.

## Cancellation sources

| Source | Token state | Meaning |
|---|---|---|
| Sibling failure | `FAIL_FAST` | One task failed and the rest of the batch was cancelled |
| Timeout | `TIMEOUT` | The configured timeout elapsed |
| Parent cancellation | `PROPAGATED_CANCELLED` | An outer scope cancelled a nested scope |

All sources use the same internal token state check. Nested `Par.map` calls inherit a parent token,
so cancellation reaches child tasks at their next checkpoint or interruptible blocking operation.
Task bodies can also throw cancellation exceptions. `ImmediateResult.outcome()` exposes the frozen
task attribution; see [Immediate results](../user-guide.md#task-future-attribution).
Interrupting the caller waiting in `map` or `runAll` does not cancel execution; those waits restore
the interrupt flag on exit. Results expose no execution-owned token or running future;
`asFuture().cancel(...)` always returns false.
