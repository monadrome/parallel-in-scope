# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn clean compile        # Compile
mvn test                 # Run all tests
mvn test -Dtest=ParallelHelperTest              # Run a single test class
mvn test -Dtest=ParallelHelperTest#testParMap    # Run a single test method
mvn clean package        # Package JAR
```

## Project Overview

**structured-concurrency** is a structured concurrency toolkit for Java 8+, built on Guava `ListenableFuture` and Alibaba `TransmittableThreadLocal`. It provides parallel collection processing with cooperative cancellation, sliding-window concurrency control, livelock detection, cross-thread context propagation, and pluggable monitoring via SPI.

- **Maven coordinates:** `io.github.linzee1:structured-concurrency:1.0.0-SNAPSHOT`
- **Java version:** source/target 1.8 (Java 8 compatible)
- **Test framework:** JUnit 5, run via Maven Surefire Plugin

## Architecture

Base package: `io.github.linzee1.concurrent` with 7 sub-packages:

| Package | Purpose | Classes |
|---|---|---|
| `scope` | API facade (user-facing) | `ParallelHelper`, `ParallelOptions`, `AsyncBatchResult`, `TaskType`, `StructuredParallel` |
| `cancel` | Cancellation subsystem | `CancellationToken`, `CancellationTokenState`, `Checkpoints`, `FatCancellationException`, `LeanCancellationException`, `PurgeService` |
| `context` | TTL/TL context propagation | `ThreadRelay`, `TaskScopeTl` |
| `context.graph` | Livelock detection | `TaskGraph`, `TaskEdge`, `TaskEdgeEntry` |
| `internal` | Execution engine + utilities | `ConcurrentLimitExecutor`, `ScopedCallable`, `Attachable`, `FutureInspector`, `ListeningExecutorAdapter` |
| `queue` | Scheduling queues | `SmartBlockingQueue`, `VariableLinkedBlockingQueue` |
| `spi` | Extension points | `TaskListener`, `ExecutorResolver`, `LivelockListener`, `ParallelLogger` |

### Execution Flow

1. **`ParallelHelper`** (facade) — entry point via `parForEach()` / `parMap()` static methods
2. **`ParallelOptions.formalized()`** — normalizes config (caps parallelism to task count, fills default timeout)
3. **`TaskGraph.logTaskPair()`** — records parent-child task dependency for livelock detection
4. **`CancellationToken`** — created and chained to parent token from `ThreadRelay`
5. **`ScopedCallable`** — wraps each task with context setup, checkpoint check, timing, SPI callbacks, cleanup
6. **`ConcurrentLimitExecutor.submitAll()`** — sliding-window submission: submits initial batch up to parallelism, then fills slots as tasks complete via `ExecutorCompletionService`
7. **`CancellationToken.lateBind()`** — after all futures submitted, wires timeout (`FluentFuture.withTimeout`), fail-fast (`Futures.allAsList`), and parent propagation. This late-binding avoids race conditions
8. **`AsyncBatchResult`** — returned to caller with `List<ListenableFuture<T>>` and `report()` for state aggregation

### Key Design Patterns

- **Sliding Window** (`ConcurrentLimitExecutor`): "submit one when one completes" pattern prevents thread pool flooding
- **Late Binding** (`CancellationToken`): timeout/fail-fast wired after all tasks are submitted to avoid premature cancellation races
- **Two-Map Context Relay** (`ThreadRelay`): parent thread's `curMap` becomes child thread's `parentMap` via TTL, propagating `CancellationToken`, `ParallelOptions`, and task names
- **Task-Type-Aware Scheduling** (`SmartBlockingQueue`): CPU_BOUND tasks' `offer()` returns `false` to trigger `ThreadPoolExecutor` rejection handler (typically `CallerRunsPolicy`), preventing queue buildup
- **Dual Cancellation Exceptions**: `LeanCancellationException` (no stack trace, zero overhead) for high-frequency scenarios; `FatCancellationException` (full stack trace) for debugging
- **SPI Decoupling**: `TaskListener`, `ExecutorResolver`, `LivelockListener`, `ParallelLogger` registered on `StructuredParallel` — no hard-coded business dependencies

### Key Dependencies

| Dependency | Purpose |
|---|---|
| Guava 33.2.1-jre | `ListenableFuture`, `FluentFuture`, `Futures`, `Graph` API, `MoreExecutors` |
| TransmittableThreadLocal 2.14.5 | Cross-thread context propagation (Alibaba TTL) |
| Lombok 1.18.30 | Compile-time code generation (provided scope) |
