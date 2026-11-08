# Migrating to v0.5

The queue public type names now describe their observable contracts. This is a source- and binary-breaking rename; no compatibility aliases are retained.

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
    <version>0.1.0</version>
</dependency>
```

Update imports; `SubmissionAwareBlockingQueue` remains in `parallel-in-scope`.
