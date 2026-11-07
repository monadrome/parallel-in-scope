# Migrating to v0.5

The queue public type names now describe their observable contracts. This is a source- and binary-breaking rename; no compatibility aliases are retained.

| Removed name | Replacement |
|---|---|
| `io.github.monadrome.parallelinscope.SmartBlockingQueue` | `io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue` |
| `io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue` | `io.github.monadrome.parallelinscope.queue.ResizableBlockingQueue` |

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

`DrainingBlockingQueue` is unchanged. Recompile consumers against `0.5.0` and update imports and type references.
