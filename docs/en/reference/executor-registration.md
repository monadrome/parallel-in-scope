# Executor registration

Registration validates every supplied executor against the `Executor` contract and reads the
executor's own structure to decide whether a task execution can be starved of a thread while
blocking on a child task. This page holds those rules; [Build the execution
topology](../user-guide.md#build-the-execution-topology) covers the basic wiring.

## The Executor contract and rejection policies

Registered executors must honor the `Executor` contract: a task handed to `execute()` runs exactly once. `build()` therefore rejects a directly registered `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` — those policies accept a task and then drop it without running it and without throwing, so nothing would ever complete its future. `AbortPolicy` (a rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are fine. An executor the library cannot see through, such as a pre-wrapped `listeningDecorator`, is accepted with a warning instead: blocking-risk detection is disabled for it.

## Visibility: register the physical pool

Registration reads a supplied executor's own structure and claims nothing it cannot read. It determines whether a task execution can be starved of a thread while blocking on a child task. Anything the library cannot see through — a pool you decorated before registering, a `ForkJoinPool`, a framework-managed executor — yields the conservative answer, and says so once at the composition root.

So register the physical pool, not a decorator. `Executors.newFixedThreadPool(n)` and `Executors.newCachedThreadPool()` return the `ThreadPoolExecutor` itself, keeping blocking-risk detection working; `Executors.newSingleThreadExecutor()` and Guava's `listeningDecorator(...)`, by contrast, return wrappers the library cannot see through. When you want a single-thread pool, construct the physical pool explicitly:

```java
ExecutorService reportPool = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
```

## Starvation-prone pools

Whether a pool is starvation-prone — whether an execution blocked on work queued to it counts toward a potential deadlock — follows where a submission goes when every worker is busy. `ThreadPoolExecutor` offers to its queue before it grows past `corePoolSize`, so a buffering queue accepts the child, parks it behind the blocked worker, and the pool never gets to add a thread — whatever `maximumPoolSize` says, finite or not. A zero-capacity handoff queue is the opposite: it refuses the offer, which forces a new worker or an explicit rejection, so a cached pool is never starvation-prone. Neither fact is ever inferred from a class name or a runtime statistic; register the physical pool you want observed.

The [executor wait report](diagnostics.md#observe-nested-work) records these waits as they happen
and warns when they first form a cycle.
