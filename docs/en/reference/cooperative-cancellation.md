# Cooperative Cancellation

## Why cooperative cancellation?

Java `Thread.interrupt()` only interrupts blocking operations such as `sleep`, `wait`, and queue operations. A CPU-bound loop can ignore the interrupt flag and continue running. `parallel-in-scope` therefore uses cooperative cancellation: the framework publishes a cancellation signal and task code checks it at explicit checkpoints.

## What the framework does automatically

| Location | Mechanism | Application work |
|---|---|---|
| Before task execution | `ScopedCallable` runs `Checkpoints.checkpoint()` | None |
| While an execution runs | Cancellation requests a runner interrupt | Use interruptible operations or client timeouts; I/O response depends on the client |
| Sliding-window submission | `SlidingWindowSubmitter` stops submitting after cancellation | None |

### Sliding-window preparation

Every element has its internal execution future prepared before cancellation and deadlines are
bound. Binding finishes before submission starts; the sliding window only controls when each
prepared task enters the executor. Cancellation therefore reaches running executions and prevents
unsubmitted tasks from starting. There are no placeholders or later delegate-binding steps.

`Par.map` waits for all results to settle and attempts bounded cleanup before returning frozen
`ImmediateResult` data. `unexitedExecutions()` reports direct executions whose exit was not confirmed;
terminal cancellation alone does not prove that an execution stopped.

Tasks that have not started are skipped, running executions receive an interrupt request, and unsubmitted
tasks do not enter the executor. An interrupt request does not guarantee that blocked I/O stops.

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

Use the no-argument `Checkpoints.checkpoint()`: it checks the current scope unconditionally, needs no task name, and cannot go stale across a rename.

The two checkpoint forms differ in what they do **outside** any scoped task:

| Checkpoint | Outside a scoped task | When cancelled |
|---|---|---|
| `Checkpoints.checkpoint()` | Silent no-op | Throws `LeanCancellationException` |
| `Checkpoints.rawCheckpoint()` | Works — no scope required; also honors the thread's interrupt flag | Throws `LeanCancellationException` |

`rawCheckpoint()` translates an observed interrupt but does not consume it: the interrupt flag stays set for the thread owner, matching the blocking adapters in the same class.

Two utilities round out the API:

| Method | Purpose |
|---|---|
| `Checkpoints.remaining()` | Read the current scoped task's remaining budget (`Optional<Duration>`); empty outside a scoped task, `Duration.ZERO` once the deadline has elapsed, and exactly `Duration.ofNanos(Long.MAX_VALUE)` when the scope has no deadline. Read-only: it never throws, never cancels, and does not touch the interrupt flag. |
| `Checkpoints.propagateCancellation(ex)` | Re-throw cancellation exceptions from a catch block |

`remaining()` lets a task execution hand its real budget to a downstream call instead of a separately configured timeout:

```java
.par(TaskKey.<User>named("user"), ioPar, () -> {
    Checkpoints.checkpoint();
    Duration budget = Checkpoints.remaining().orElse(configuredClientTimeout);
    if (budget.isZero() || budget.equals(Duration.ofNanos(Long.MAX_VALUE))) {
        // zero means the deadline is spent — refuse the call rather than passing a
        // zero timeout to a client that reads zero as "wait forever"
    }
    return fetchWithBudget(id, budget);
})
```

A positive budget does not prove the scope is uncancelled (fail-fast may already be committed); keep a `checkpoint()` before relying on it.

Add checkpoints at a reasonable granularity: every N iterations of a long loop, between expensive phases, or at the entry to a recursive walk. Check cancellation before starting expensive I/O and configure the client's timeouts; very short functions normally need no extra checkpoint.

## Do not swallow cancellation

```java
global.par(ParId.of("myExecutor")).map(items, item -> {
    try {
        return riskyOperation(item);
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while loading item", ex);
    } catch (IOException ex) {
        Checkpoints.propagateCancellation(ex);
        log.error("failed", ex);
        return defaultValue;
    }
}, options);
```

This example degrades recoverable I/O failures only; cancellation exceptions escape the catch clauses.
`propagateCancellation` rethrows cancellation exceptions or a committed scope cancellation. It does
not restore the flag for an ordinary `InterruptedException`: a Function must restore and translate
that exception with its original cause, as above. A group member's Callable can throw it directly.

## Replace `Thread.sleep()` with `Checkpoints.checkSleep()`

Inside a `Par` task execution, use `Checkpoints.checkSleep()` instead of `Thread.sleep()`:

```java
// not recommended
Thread.sleep(1000);  // you must handle InterruptedException yourself

// recommended
Checkpoints.checkSleep(1000, TimeUnit.MILLISECONDS);  // converts the interruption for you
Checkpoints.checkSleep(Duration.ofSeconds(1));        // same, with a Duration
```

`Checkpoints.checkSleep()` uniformly converts `InterruptedException` into `LeanCancellationException`,
so interruption-driven and cooperative cancellation surface as the same exception type, and a normal
cancellation does not collect a useless stack trace.

## Logging after cancellation

When cancellation has already settled a task, an execution that then exits by throwing has no result
left to carry; the engine logs it from the `ExecutionPhaseHintFuture` logger as
`task '<name>' failed after cancellation`, with the original exception. The level depends on where
the exception came from, never on its type:

| Late exception, no suppressed exceptions | Level |
|---|---|
| Thrown by a `Checkpoints` call that read the committed cancellation | `FINE` |
| A raw `InterruptedException` from a JDK blocking call that the engine's cancellation interrupt woke on this run | `FINE` |
| Anything else: your own `CancellationException` or `LeanCancellationException`, a wrapped one, an `InterruptedException` the engine did not cause, or any business failure | `WARNING` |

Any late exception carrying a suppressed exception, typically a failing `close()` in
try-with-resources, stays a `WARNING` with the suppressed failure attached. The log level never
changes the result: read cancellation counts from `outcomeCounts()` or `TaskOutcome`, not from
the number of warnings.

A `Checkpoints` blocking adapter interrupted while the scope is not cancelled throws the same
`LeanCancellationException` type with the `InterruptedException` as cause, and the task's outcome is
`USER_FAILURE`: an interrupt alone does not say who sent it.

## Cancellation sources

| Source | Token state | Meaning |
|---|---|---|
| Sibling failure | `FAIL_FAST` | One task failed and the rest of the batch was cancelled |
| Timeout | `TIMEOUT` | The configured timeout elapsed |
| Parent cancellation | `PROPAGATED_CANCELLED` | An outer scope cancelled a nested scope |
| External source `cancelOn` | `CANCELLED` | A caller-held `ListenableFuture` completing in any way — success, failure, or cancellation — cancels the whole batch or group; attribution is first-wins and a winning source records `GROUP_CANCELLED`; running executions are interrupted, as for every other cancellation source |

All sources use the same internal token state check. The external input is declared on the batch's
`BatchOptions` or on `GroupStart` (before the first member); task executions never poll an application
flag. See [Cancel when a request completes](../user-guide.md#request-cancellation).
Nested `Par.map` calls inherit a parent token,
so cancellation reaches child tasks at their next checkpoint or interruptible blocking operation.
Task executions can also throw cancellation exceptions. `ImmediateResult.outcome()` exposes the frozen
task attribution; see [Immediate results](../user-guide.md#task-future-attribution).
Interrupting the caller waiting in `map` or `runAll` does not cancel execution; those waits restore
the interrupt flag on exit. Results expose no execution-owned token or running future;
`asFuture().cancel(...)` always returns false.

## Nested scope cancellation propagation

When a `Par.map()` call runs inside another `Par.map()`, the framework propagates cancellation
automatically through the `CancellationToken` parent–child chain:

```text
Outer Par.map(["A", "B", "C"])
  ├── A → inner Par.map([1, 2, 3])    ← owns a child CancellationToken
  ├── B → throws an exception          ← triggers the outer fail-fast
  └── C → inner Par.map([4, 5, 6])    ← owns a child CancellationToken
```

When B fails:

1. The outer token becomes `FAIL_FAST`.
2. The child tokens of A and C become `PROPAGATED_CANCELLED` through the parent–child chain.
3. The inner tasks of A and C respond at their next checkpoint or interruptible blocking
   operation; ordinary I/O still depends on the client.

You do not orchestrate this propagation yourself — provided the inner tasks carry enough
checkpoints.
