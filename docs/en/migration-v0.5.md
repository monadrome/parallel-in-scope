# Migrating to v0.5

The queue public type names now describe their observable contracts. This is a source- and binary-breaking rename; no compatibility aliases are retained.

## Termination and exit-observation names

The v0.5 line separates transport completion, logical termination, physical execution exit, and
post-exit telemetry. The names below are source- and binary-breaking changes; recompile consumers
and update imports and enum switches.

| Before | v0.5 replacement | Meaning |
|---|---|---|
| `TaskCompletion<T>` | `TaskExitObservation<T>` | Immutable telemetry published after the task future is terminal and execution has exited (or was prevented from starting) |
| `TaskStatus.TERMINATED` | `TaskStatus.RESULT_SETTLED` | The logical result is settled while execution may still be unwinding |
| `ExecutionDisposition.ENTERED` | `ExecutionDisposition.CLAIMED` | The execution claim was acquired; it does not promise that user code has started or exited |
| `ExecutionDisposition.NOT_ENTERED` | `ExecutionDisposition.NOT_CLAIMED` | The execution claim was not acquired; framework preparation may still have happened |
| package-private `TaskGroup.completionFuture()` | `convergenceFuture()` | Internal group convergence/report transport |
| package-private `TaskBatch.completionFuture()` | `observationsFuture()` | Internal aggregate observation transport |

Before:

```java
TaskCompletion<Account> snapshot = task.completionFuture().get();
if (snapshot.disposition() == ExecutionDisposition.ENTERED) {
    record(snapshot);
}
```

After:

```java
TaskExitObservation<Account> snapshot = task.completionFuture().get();
if (snapshot.disposition() == ExecutionDisposition.CLAIMED) {
    record(snapshot);
}
```

Use the lifecycle layers for the corresponding question:

1. `Future.isDone()` reports transport completion.
2. `ImmediateResult.termination()` reports the frozen logical termination.
3. `executionExitConfirmed()` is the resource-release gate for direct executions.
4. `TaskExitObservation<T>` carries final timing, outcome, failure, and value telemetry after exit.
5. `ParRuntime.awaitQuiescence(...)` waits for a closed runtime's admitted executions and observation scopes.

`ImmediateResult.asFuture()` keeps its name and remains a read-only, already-completed Guava adapter;
it does not represent execution exit or runtime quiescence. `TaskTermination` is now the canonical task result. New code reads its kind, cause, cancellation reason, and claim disposition directly; scope results use `ScopeTermination`.

## TaskGroup member values

The v0.5 development line also removes the recursive `Tuple2` member shape. `GroupStep` and
`CombinedGroupStep` no longer carry an aggregate member type, and `TaskGroupResult<R>` carries only
the terminal result type. `combine` and `combineValues` receive a non-null `GroupValues` snapshot.

Before:

```java
TaskGroupResult<Tuple2<User, Account>, Profile> result = runtime
        .group("profile", timeout)
        .par(userKey, ioPar, this::loadUser)
        .par(accountKey, httpPar, this::loadAccount)
        .combine(profileKey, cpuPar, values -> buildProfile(values.first(), values.second()))
        .runAll();
```

After:

```java
TaskGroupResult<Profile> result = runtime
        .group("profile", timeout)
        .par(userKey, ioPar, this::loadUser)
        .par(accountKey, httpPar, this::loadAccount)
        .combineValues(profileKey, cpuPar, values -> buildProfile(
                values.valueOf(userKey), values.valueOf(accountKey)))
        .runAll();
```

`GroupValues.typedValues()` and the public `Tuple2` type are removed. Use `valueOf(TaskKey<T>)`
for compile-time type safety, or `valueAt(int)`/`valueOf(String)` for dynamic access. Recompile
consumers against the v0.5 line.

## Context-propagating direct executors

The project-specific `ContextExecutors.directExecutor()` method is renamed to
`ContextExecutors.directContextExecutor()` to avoid colliding with Guava's `MoreExecutors.directExecutor()`.
The renamed method captures Alibaba TransmittableThreadLocal (TTL) context at each `execute` call. This is
the reusable default for commands whose context belongs to the call site. The old creation-time
behavior is available as `ContextExecutors.registrationContextExecutor()` and is intended for a
Future listener or callback that must retain the context from registration. `lazyDirectExecutor()`
is removed; replace it with `directContextExecutor()`.

| Removed name | Replacement |
|---|---|
| `io.github.monadrome.parallelinscope.SmartBlockingQueue` | `io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue` |
| `io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue` | `io.github.monadrome.queues.ResizableBlockingQueue` |

Before:

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SmartBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

After:

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SubmissionAwareBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

`rejectEnqueue`, `capacity()`, and `setCapacity(int)` keep their previous behavior. `TaskType` remains diagnostic and does not change queue admission; only `rejectEnqueue(true)` requests refusal through `SubmissionAwareBlockingQueue`.

`DrainingBlockingQueue` and `ResizableBlockingQueue` are now published by `io.github.monadrome:blocking-queues` under `io.github.monadrome.queues`. Add this dependency when using either standalone queue:

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>blocking-queues</artifactId>
    <version>0.5.0</version>
</dependency>
```

Update imports; `SubmissionAwareBlockingQueue` remains in `parallel-in-scope`.


The removed public `TaskOutcome` type is replaced by `TaskTermination` and `ScopeTermination`; callers must recompile and migrate `outcome()` reads.
