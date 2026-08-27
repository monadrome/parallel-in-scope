# AGENTS.md

## Project

**parallel-in-scope** is a structured-concurrency toolkit for Java 8+ built on Guava
`ListenableFuture` and Alibaba `TransmittableThreadLocal`. It provides parallel batch
execution with cooperative cancellation, sliding-window concurrency, fail-fast
propagation, cross-thread context propagation, and pluggable monitoring via SPI.

- Maven coordinates: `io.github.huatalk:parallel-in-scope:0.2.0`
- Java source/target: 1.8 (tests compiled with release 11)
- Test framework: JUnit 5 via Maven Surefire

## Commands

Run all commands from the repository root unless noted.

```bash
# Compile
mvn clean compile

# Run all tests
mvn test

# Run one test class or method
mvn test -Dtest=<ClassName>
mvn test -Dtest=<ClassName>#<methodName>

# Package
mvn clean package

# Format sources
mvn spotless:apply

# Verify (tests + package checks)
mvn clean verify

# Demo module (standalone)
cd demo && mvn test
```

## Architecture

Base package: `io.github.huatalk.parallelinscope`.

| Package | Responsibility |
|---|---|
| `scope` | API facade: `GlobalPar`, `Par`, `ExecutionOptions`, `AsyncBatchResult`, `TaskType`, `GlobalExecutionPolicy`, etc. |
| `cancel` | Cancellation subsystem: `CancellationToken`, `Checkpoints`, `LeanCancellationException`, `FatCancellationException`, `HeuristicPurger` |
| `context` | TTL/TL context propagation: `ThreadRelay`, `TaskScopeTl` |
| `context.graph` | Livelock and cycle detection: `TaskGraph`, `TaskEdge`, `TaskEdgeEntry` |
| `internal` | Execution engine: `ConcurrentLimitExecutor`, `ScopedCallable`, `FutureInspector`, `ListenableCompletionService` |
| `queue` | Scheduling queues: `SmartBlockingQueue`, `VariableLinkedBlockingQueue`, `DrainingBlockingQueue` |
| `spi` | Extension points: `TaskListener`, `LivelockListener`, `ExecutionPhase` |

### Execution Flow

1. `GlobalPar` registers executors and produces scoped `Par` instances.
2. `Par.map/forEach` builds `ExecutionOptions`, creates a `BatchExecutionContext`,
   and logs task pairs in `TaskGraph` for cycle detection.
3. `CancellationToken` is created and chained to the parent token via `ThreadRelay`.
4. `ScopedCallable` wraps each task with context setup, checkpoint checks, timing,
   SPI callbacks, and cleanup.
5. `ConcurrentLimitExecutor.submitAll()` submits through `ListenableCompletionService`;
   the returned `FutureRunnable` is the exact queued object.
6. `CancellationToken.lateBind()` wires timeout, fail-fast, and parent propagation
   only after all futures are submitted.
7. `AsyncBatchResult` returns the futures and `report()` aggregates batch state.

## Key Conventions

- **Java 8 compatibility**: do not use Java 9+ APIs in `src/main/java`.
- **Package-level `@ParametersAreNonnullByDefault`**: every package has
  `package-info.java`. Only annotate exceptions with `@Nullable`.
- **Public API / SPI** use `javax.annotation.Nullable` (JSR-305, provided scope).
- **Internal code** uses `org.checkerframework.checker.nullness.qual.Nullable`
  (Checker Framework, provided scope).
- **JUL logging**: the framework logs through `java.util.logging.Logger`; users
  bridge to their logging backend.
- **API stability**: pre-stable; public APIs and SPI may change between `0.x`
  revisions without compatibility shims.

## Testing

- Prefer targeted tests: `mvn test -Dtest=<ClassName>#<methodName>`.
- Add or update tests when changing cancellation, context propagation, executor
  binding, or queue behavior.
- Run `mvn test` before finishing changes that touch the execution engine or
  public API.
- Run `mvn spotless:apply` if formatting checks fail.

## Safety And Permissions

Allowed without asking:
- Read source files and run targeted Maven test/lint commands.
- Apply `mvn spotless:apply` to changed files.

Ask before:
- Installing Maven dependencies or upgrading plugin versions.
- Deleting files or directories.
- Running full release builds, PIT mutation tests, or `mvn deploy`.
- Committing, pushing, or opening PRs.

Never commit secrets, `.env` files, GPG keys, or repository credentials.

## Reference Documents

- `README.md` - User-facing overview, quick start, and documentation links.
- `docs/en/user-guide.md` - Full user guide; read when changing user-facing behavior.
- `docs/en/migration-v0.2.md` - Breaking changes from the `0.1.x` API.
- `adr/AGENTS.md` - Rules for adding or updating Architecture Decision Records.
- `demo/README.en.md` - Runnable examples and article catalog.
- `demo/architecture-constraints.md` - Allowed APIs and module boundaries for the demo.
