# Idea Graveyard

> This page condenses the full-length Chinese original under `docs/zh/design/idea-graveyard.md` (published at `/zh/design/idea-graveyard/`), which carries the complete reasoning per entry and is the version the project's design-document routes consult.
>
> Historical examples in this note may use pre-0.3 API. Current application wiring uses `ParRuntime.builder()`, the per-scope option types (`BatchOptions`/`TaskOptions`), and `TaskKey<T>` for typed group members; see the [user guide](../user-guide.md) and the [v0.4 migration guide](../migration-v0.4.md).

This page records features we seriously considered but ultimately decided not to implement, together with the reasons for rejecting them.

## Configurable failure policies

The library uses fail-fast cancellation as its batch contract. Adding `IGNORE`, `COLLECT`, and other policies would multiply result states and make cancellation ownership unclear. Applications that need partial success can catch the business exceptions they can degrade inside the task function and return an explicit domain result. Call `Checkpoints.checkpoint()` before degrading: fail-fast, deadlines, and `cancelOn` interrupt siblings, and a broad `catch (Exception e)` would log that cancellation as a downstream failure and clear the interrupt flag, so the task code keeps running; the element itself is still attributed to the scope's cancellation. Degraded elements count as successes in the batch result, so the application keeps its own failure count.

## Fluent task orchestration

Methods such as `thenApply`, `thenCompose`, and `exceptionally` would turn the library into another future-composition framework. `parallel-in-scope` owns bounded batch execution; orchestration belongs to Guava or `CompletableFuture` at the application boundary.

## Built-in retry

Retries need a policy for backoff, idempotency, error classification, and attempt budgets. Those decisions depend on the remote system, so a generic retry engine would be misleading. Use a dedicated resilience library or implement retry in the task function.

## Intra-batch priority

Priority scheduling can starve work and conflicts with the predictable sliding-window model. If priority is required, submit separate batches or use an executor policy designed for that workload.

## Spring Boot starter

The core library is framework-neutral and targets Java 8. A starter would add Spring Boot 2.x/3.x compatibility and auto-configuration maintenance for a small amount of wiring. Explicit `ParRuntime.builder()` setup keeps the dependency boundary clear.

## Independent deadlines for batch elements

`Par.map` shares one batch deadline; it has no separate timeout per element. Group members and
combines already accept `TaskOptions.timeout(Duration)`, capped by the group's effective deadline.
A tighter member deadline cancels the group as TIMEOUT. Use a typed group for independently budgeted
work and client timeouts for individual blocking operations; do not add per-element timers to map.

## `CompletableFuture` as the public result type

The implementation relies on Guava future primitives for listener executors, cancellation, and completion aggregation. Returning `CompletableFuture` would create two future semantics and hide the executor choices that matter for this library. An adapter can be kept at the application boundary.

## Streaming and reactive input

The API deliberately models a finite batch. Streaming, backpressure, and reactive cancellation require a different lifecycle model and are better served by Reactor or a similar framework.

## Heterogeneous task composition

Typed TaskGroup already coordinates heterogeneous Callables using names, declared types, and a
keyed `GroupValues` snapshot: `runtime.group(...).par(...).par(...).runAll()`. What remains rejected is
an untyped `invokeAll` overload family or a general dependency graph. Use the existing group for
independent typed tasks and its optional terminal combine for a single all-successful join.

## Adaptive concurrency

Automatically changing parallelism makes latency and resource behavior difficult to predict. The caller should own the concurrency limit and adjust it using application metrics and an explicit policy.

## A library-defined throwing mapper

`Par.map` takes the standard `java.util.function.Function`, whose `apply` cannot declare checked exceptions, so a task execution that does IO must wrap what it throws. A library-defined `ThrowingFunction` — or a `map` overload taking one — was considered and rejected. The kernel already runs every task execution as a `Callable` and attributes cancellation, deadlines, and user-code failure identically under either signature, so the change buys no structured-concurrency guarantee; it only widens the public surface with a second functional interface and costs a breaking, binary-incompatible signature change plus a migration for `Function`-typed call sites. Wrapping stays caller policy: catch and rethrow unchecked, return an explicit domain result, or move that work to a group member, which takes a `Callable`; a single-member group covers unary work. The direction is closed by explicit user decision, not deferred; the conclusion and the rejection reason for each alternative signature are recorded in `design/decision-log.md`.

## A per-submission caller-thread fallback (`runOnCallerThread`)

`TaskOptions`/`BatchOptions` briefly carried an option that ran a task execution on the submitting thread when the bound executor rejected it. It was removed before 0.3.0 shipped and never appeared in a released version. It put user code on threads the caller did not expect — every borrowed-thread path needed its own isolation argument — and it duplicated a decision the JDK already assigns to the executor's `RejectedExecutionHandler`, declared once at registration. Two capabilities went with it: the option worked with any `ExecutorService` while `CallerRunsPolicy` exists only on `ThreadPoolExecutor` (other pools need a decorate-and-`run()` wrapper, shown in the migration guide); and the library-internal fallback throttled batch submission by occupying the submitting thread, a timing no executor-side handler reproduces. The supported paths are `MoreExecutors.newDirectExecutorService()` and `CallerRunsPolicy`; the kernel's `run()` isolates the borrowed thread's interrupt flag and submission scope regardless of which one is used.

## An unbounded timeout factory (`unbounded()`)

A third choice next to `timeout(...)` and `inheritTimeout(...)` — `BatchOptions.unbounded(name)`, `TaskOptions.unbounded()`, or a group entry without a budget — was considered and rejected. The deadline is the only source of liveness for a synchronous scope: `map` and `runAll` wait uninterruptibly, caller interruption does not cancel the execution, and the public API exposes no running handle, so in a top-level scope without a deadline one execution that ignores interruption hangs the calling thread with no way out. The two-choice rule is deliberate: omitting a timeout does not compile, and `unbounded()` would remove no mistake, only give "no limit" a sanctioned name. Long-running work states a generous operational bound instead — `BatchOptions.timeout("nightly-rebuild", Duration.ofHours(6))` — which doubles as production protection and costs one timer per scope, removed when the scope settles; nested calls use `inheritTimeout(...)`. Astronomical values such as `ChronoUnit.FOREVER` are accepted and resolve to a finite deadline, but when an execution never exits the cleanup wait after cancellation is then practically unbounded as well; set `closeGrace(Duration.ZERO)` to cancel without waiting. The direction reopens only for a concrete case where every finite bound is harmful rather than inconvenient, together with a liveness design for a scope without a deadline.

## Task progress reporting

A task/batch progress API — live counts, percentages, an event stream, or heartbeats — is not added. `map`/`runAll` execute synchronously on the calling thread and the public surface exposes no running handle, so the only observation point is scope exit, which already returns frozen terminal observations (`TaskExitObservation`, `TaskGroupResult`). A live progress stream would serve only a thread watching from outside the scope, the interaction shape structured concurrency removes by design, and it would become another observability mechanism beside the executor wait graph and frozen results, with its own subscription, replay, backpressure, and observer-executor contract. Progress is also business semantics: only the task code knows what the total and each increment mean, so publishing counters from inside the task function to your own metrics backend is both the supported extension point and the more accurate model. A primary-source survey of progress mechanisms across twelve systems (2026-11-12) confirmed the contract surface this would require; the direction is closed by explicit user decision (2026-11-15) and recorded in `design/decision-log.md`.

## Automatic cancelled-task purge

The library once purged cancelled tasks from user executor queues automatically; the feature was removed in 0.3.0. A cancelled `Future` can stay referenced in a `ThreadPoolExecutor` queue until a worker consumes it, but making the library clean that up required a per-executor maintenance protocol on the latency-sensitive cancellation path — coalescing delays, admission thresholds, and ordering guarantees that cost more than the stale references they removed. Queue cleanup belongs to the executor's owner: call `ThreadPoolExecutor.purge()` at your own maintenance boundary. The event-coalesced purge design is preserved on the `dev/experimental` branch should automatic purge ever return.

## Workload declarations and blocking detection (`TaskType`)

Version 0.4 let tasks and entries declare a `TaskType` (`COMPUTE`, `BLOCKING`, `UNKNOWN`), logged a warning when the two declarations disagreed, and timed executions on `COMPUTE` entries with thread CPU time to flag blocking work. All of it was removed for 0.5.0. The comparison only caught a task whose author knew it blocked and still wired it to the wrong pool, while the common incident is blocking the author does not know about, which carries no declaration. The CPU-time screen was a heuristic that GC, safepoints, and CPU-quota throttling skew, with uncalibrated thresholds and a timing cost inside every screened execution. Enforcing the declaration instead would refuse honest declarations, since a compute task that waits for a nested batch is `BLOCKING`, and would turn a wrong label into a failed request. To keep a pool-purpose check, give each pool's `Par` its own application type so that wiring the wrong pool fails to compile; to find blocking calls on compute threads, run a blocking-call detector such as BlockHound in tests or staging. Reopen only with a blocking signal that is cheap enough to leave on and reliable without per-environment calibration (ADR 0016).

## Thread-pool starvation sampling

Version 0.4 offered `ParRuntime.openThreadPoolStarvationObservation(Duration)`, a closeable scope that sampled registered `ThreadPoolExecutor` counters and reported `POTENTIAL_STARVATION` when every worker stayed busy with work queued and no task completed. It was removed for 0.5.0. Its target was work submitted directly to a pool, which the library does not run; executor metrics (Micrometer's `ExecutorServiceMetrics`, JMX, or an APM agent) export the same counters continuously and can alert while a request is still stuck, whereas the scope reported only at close. Using it correctly needed a separate control thread and a window of several sampling intervals, and an unclosed scope kept the runtime's services alive after `close()`. Watch pool size, active count, queue depth, and completed task count in your metrics stack instead; for work admitted through the library, `executorWaitReport()` still reports waits-for cycles.

## What will not be added

The project will stay focused on structured batch execution, cooperative cancellation, bounded scheduling, context propagation, and executor deadlock diagnostics. Features outside that boundary should be integrated explicitly rather than hidden in the core API.
