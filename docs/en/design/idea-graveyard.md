# Idea Graveyard

> Historical examples in this note may use pre-0.2 API. Current application wiring uses `ParRuntime.builder()` and the per-scope option types (`BatchOptions`/`TaskOptions`); see the [v0.3 migration guide](../migration-v0.3.md).

This page records features we seriously considered but ultimately decided not to implement, together with the reasons for rejecting them.

## Configurable failure policies

The library uses fail-fast cancellation as its batch contract. Adding `IGNORE`, `COLLECT`, and other policies would multiply result states and make cancellation ownership unclear. Applications that need partial success can catch errors inside the task function and return an explicit domain result.

## Fluent task orchestration

Methods such as `thenApply`, `thenCompose`, and `exceptionally` would turn the library into another future-composition framework. `parallel-in-scope` owns bounded batch execution; orchestration belongs to Guava or `CompletableFuture` at the application boundary.

## Built-in retry

Retries need a policy for backoff, idempotency, error classification, and attempt budgets. Those decisions depend on the remote system, so a generic retry engine would be misleading. Use a dedicated resilience library or implement retry in the task function.

## Intra-batch priority

Priority scheduling can starve work and conflicts with the predictable sliding-window model. If priority is required, submit separate batches or use an executor policy designed for that workload.

## Spring Boot starter

The core library is framework-neutral and targets Java 8. A starter would add Spring Boot 2.x/3.x compatibility and auto-configuration maintenance for a small amount of wiring. Explicit `ParRuntime.builder()` setup keeps the dependency boundary clear.

## Per-task timeout

The current timeout is a batch-level contract. Per-task deadlines would make result states and cancellation races substantially harder to explain. If individual deadlines are required, enforce them in the task function or an application-level client.

## `CompletableFuture` as the public result type

The implementation relies on Guava future primitives for listener executors, cancellation, and completion aggregation. Returning `CompletableFuture` would create two future semantics and hide the executor choices that matter for this library. An adapter can be kept at the application boundary.

## Streaming and reactive input

The API deliberately models a finite batch. Streaming, backpressure, and reactive cancellation require a different lifecycle model and are better served by Reactor or a similar framework.

## Heterogeneous task composition

An `invokeAll`-style API for unrelated callable types cannot provide a useful type-safe aggregate result without a large overload matrix. Use typed batches and compose their results explicitly.

## Adaptive concurrency

Automatically changing parallelism makes latency and resource behavior difficult to predict. The caller should own the concurrency limit and adjust it using application metrics and an explicit policy.

## A library-defined throwing mapper

`Par.map` takes the standard `java.util.function.Function`, whose `apply` cannot declare checked exceptions, so a task body that does IO must wrap what it throws. A library-defined `ThrowingFunction` — or a `map` overload taking one — was considered and rejected. The kernel already runs every task body as a `Callable` and attributes cancellation, deadlines, and `USER_FAILURE` identically under either signature, so the change buys no structured-concurrency guarantee; it only widens the public surface with a second functional interface and costs a breaking, binary-incompatible signature change plus a migration for `Function`-typed call sites. Wrapping stays caller policy: catch and rethrow unchecked, return an explicit domain result, or move that work to a group member, which takes a `Callable`; a single-member group covers unary work. The direction is closed by explicit user decision, not deferred; the analysis is kept in `design/par-map-throwing-function-v0.3-proposal.md`.

## A per-submission caller-thread fallback (`runOnCallerThread`)

`TaskOptions`/`BatchOptions` briefly carried an option that ran a task body on the submitting thread when the bound executor rejected it. It was removed before 0.3.0 shipped and never appeared in a released version. It put user code on threads the caller did not expect — every borrowed-thread path needed its own isolation argument — and it duplicated a decision the JDK already assigns to the executor's `RejectedExecutionHandler`, declared once at registration. Two capabilities went with it: the option worked with any `ExecutorService` while `CallerRunsPolicy` exists only on `ThreadPoolExecutor` (other pools need a decorate-and-`run()` wrapper, shown in the migration guide); and the library-internal fallback throttled batch submission by occupying the submitting thread, a timing no executor-side handler reproduces. The supported paths are `MoreExecutors.newDirectExecutorService()` and `CallerRunsPolicy`; the kernel's `run()` isolates the borrowed thread's interrupt flag and submission scope regardless of which one is used.

## What will not be added

The project will stay focused on structured batch execution, cooperative cancellation, bounded scheduling, context propagation, and executor deadlock diagnostics. Features outside that boundary should be integrated explicitly rather than hidden in the core API.
