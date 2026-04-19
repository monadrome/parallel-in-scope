# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn clean compile        # Compile
mvn test                 # Run all tests
mvn test -Dtest=ParTest              # Run a single test class
mvn test -Dtest=ParTest#testParMap    # Run a single test method
mvn clean package        # Package JAR
```

## Project Overview

**vformation** is a structured concurrency toolkit for Java 8+, built on Guava `ListenableFuture` and Alibaba `TransmittableThreadLocal`. It provides parallel collection processing with cooperative cancellation, sliding-window concurrency control, livelock detection, cross-thread context propagation, and pluggable monitoring via SPI.

- **Maven coordinates:** `io.github.huatalk:vformation:1.0.0`
- **Java version:** source/target 1.8 (Java 8 compatible)
- **Test framework:** JUnit 5, run via Maven Surefire Plugin

## Architecture

Base package: `io.github.huatalk.vformation` with 7 sub-packages:

| Package | Purpose | Classes |
|---|---|---|
| `scope` | API facade (user-facing) | `Par`, `ParOptions`, `AsyncBatchResult`, `TaskType`, `ParConfig` |
| `cancel` | Cancellation subsystem | `CancellationToken`, `CancellationTokenState`, `Checkpoints`, `FatCancellationException`, `LeanCancellationException`, `HeuristicPurger` |
| `context` | TTL/TL context propagation | `ThreadRelay`, `TaskScopeTl` |
| `context.graph` | Livelock detection | `TaskGraph`, `TaskEdge`, `TaskEdgeEntry` |
| `internal` | Execution engine + utilities | `ConcurrentLimitExecutor`, `ScopedCallable`, `FutureInspector`, `FutureState` |
| `queue` | Scheduling queues | `SmartBlockingQueue`, `VariableLinkedBlockingQueue` |
| `spi` | Extension points | `TaskListener`, `ExecutorResolver`, `LivelockListener` |

### Execution Flow

1. **`Par`** (facade) — entry point via `forEach()` / `map()` instance methods
2. **`ParOptions.formalized()`** — normalizes config (caps parallelism to task count, fills default timeout)
3. **`TaskGraph.logTaskPair()`** — records parent-child task dependency for livelock detection
4. **`CancellationToken`** — created and chained to parent token from `ThreadRelay`
5. **`ScopedCallable`** — wraps each task with context setup, checkpoint check, timing, SPI callbacks, cleanup
6. **`ConcurrentLimitExecutor.submitAll()`** — sliding-window submission: submits initial batch up to parallelism, then fills slots as tasks complete via `ExecutorCompletionService`
7. **`CancellationToken.lateBind()`** — after all futures submitted, wires timeout (`FluentFuture.withTimeout`), fail-fast (`Futures.allAsList`), and parent propagation. This late-binding avoids race conditions
8. **`AsyncBatchResult`** — returned to caller with `List<ListenableFuture<T>>` and `report()` for state aggregation

### Key Design Patterns

- **Sliding Window** (`ConcurrentLimitExecutor`): "submit one when one completes" pattern prevents thread pool flooding
- **Late Binding** (`CancellationToken`): timeout/fail-fast wired after all tasks are submitted to avoid premature cancellation races
- **Two-Map Context Relay** (`ThreadRelay`): parent thread's `curMap` becomes child thread's `parentMap` via TTL, propagating `CancellationToken`, `ParOptions`, and task names
- **Task-Type-Aware Scheduling** (`SmartBlockingQueue`): CPU_BOUND tasks' `offer()` returns `false` to trigger `ThreadPoolExecutor` rejection handler (typically `CallerRunsPolicy`), preventing queue buildup
- **Dual Cancellation Exceptions**: `LeanCancellationException` (no stack trace, zero overhead) for high-frequency scenarios; `FatCancellationException` (full stack trace) for debugging
- **SPI Decoupling**: `TaskListener`, `ExecutorResolver`, `LivelockListener` registered on `ParConfig` — no hard-coded business dependencies
- **JUL Logging**: Framework uses `java.util.logging.Logger` directly; users bridge to SLF4J/Log4j2 via standard JUL handlers

### Key Dependencies

| Dependency | Purpose |
|---|---|
| Guava 33.2.1-jre | `ListenableFuture`, `FluentFuture`, `Futures`, `Graph` API, `MoreExecutors` |
| TransmittableThreadLocal 2.14.5 | Cross-thread context propagation (Alibaba TTL) |
| Lombok 1.18.30 | Compile-time code generation (provided scope) |
| JSR-305 3.0.2 | `javax.annotation.Nullable`/`@Nonnull` for Public API (provided scope) |
| Checker Framework checker-qual 3.42.0 | `org.checkerframework.checker.nullness.qual.Nullable` for Internal code (provided scope) |

### Nullability Conventions

The project uses a **hybrid nullability annotation strategy** with package-level default `@NonNull`:

**Package-level default**: Every package has `package-info.java` with `@javax.annotation.ParametersAreNonnullByDefault`, so all method parameters are non-null by default. Only `@Nullable` annotations are needed on exceptions.

**Annotation source rules by class category:**

| Category | Classes | Annotation Source |
|----------|---------|-------------------|
| Public API | `Par`, `ParOptions`, `AsyncBatchResult`, `ParConfig`, `Checkpoints`, `TaskType`, `CancellationToken`, `CancellationTokenState` | `javax.annotation.Nullable` (JSR-305) |
| SPI | `TaskListener`, `ExecutorResolver`, `LivelockListener` | `javax.annotation.Nullable` (JSR-305) |
| Internal | All other classes | `org.checkerframework.checker.nullness.qual.Nullable` (Checker Framework) |

**When to add `@Nullable`:**
- Return values that can be `null` (e.g., `getExecutor()` returns null if not found)
- Parameters that explicitly accept `null`
- Do NOT add `@Nonnull`/`@NonNull` on parameters — covered by package default

**Checker Framework TYPE_USE style** (Internal classes only): Use `@Nullable` before the type, e.g., `public static @Nullable Data data()`
