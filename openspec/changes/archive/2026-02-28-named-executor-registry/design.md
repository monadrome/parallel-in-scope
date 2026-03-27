## Context

Currently `ParallelHelper.map()` and `forEach()` require callers to pass both a `ListeningExecutorService` instance and an optional `String executorName` at every call site. There are 4 public overloads (2 with name, 2 without). This leads to scattered executor references, potential name-instance mismatches, and inconsistent usage across a codebase.

The project already has `ListeningExecutorAdapter.adapt()` for wrapping plain `ExecutorService` into `ListeningExecutorService`, making it natural to accept any `ExecutorService` at registration time.

## Goals / Non-Goals

**Goals:**
- Provide a central, thread-safe executor registry on `StructuredParallel` where users register executors by name once at startup.
- Replace the entire `ParallelHelper` public API with exactly two methods: `map(String executorName, List, Function, ParallelOptions)` and `forEach(String executorName, Collection, Consumer, ParallelOptions)`.
- Remove all existing overloads that accept `ListeningExecutorService` directly — the executor name is the only way to specify an executor.
- Automatically bridge registered executors into the purge/livelock subsystem.
- Update all existing tests to use the new API.

**Non-Goals:**
- Executor lifecycle management (shutdown, health checks) — callers still own executor lifecycle.
- Dynamic executor reconfiguration at runtime (resize, replace) — users unregister and re-register if needed.
- Auto-discovery or classpath scanning of executors.
- Backward compatibility — this is an intentional breaking change to simplify the API.

## Decisions

### 1. Registry location: `StructuredParallel` static methods

**Decision:** Add `registerExecutor(String, ExecutorService)`, `unregisterExecutor(String)`, and `getExecutor(String)` static methods to `StructuredParallel`.

**Rationale:** `StructuredParallel` is already the central configuration hub for all SPI and global settings. Adding executor registry here follows the established pattern (logger, task listeners, executor resolver, livelock listeners all live here).

### 2. Storage: `ConcurrentHashMap<String, ListeningExecutorService>`

**Decision:** Use a `ConcurrentHashMap` for the registry storage.

**Rationale:** Thread-safe, non-blocking reads, suits the register-once-read-many access pattern. Matches the thread-safety approach of other `StructuredParallel` fields.

### 3. Adaptation at registration time

**Decision:** Call `ListeningExecutorAdapter.adapt(executorService)` inside `registerExecutor()`, so users can register any `ExecutorService` (including plain `ThreadPoolExecutor`).

**Rationale:** Adaptation at registration time means every subsequent lookup returns a `ListeningExecutorService` without repeated wrapping.

### 4. Automatic fallback for `ExecutorResolver`

**Decision:** When no explicit `ExecutorResolver` is set, `StructuredParallel.resolveThreadPool(name)` will also check the registry: if the registered executor wraps a `ThreadPoolExecutor`, unwrap and return it.

**Rationale:** Eliminates the need for users to implement `ExecutorResolver` just for purge/livelock support. If an explicit `ExecutorResolver` is set, it takes priority.

### 5. Replace all `ParallelHelper` public methods (BREAKING)

**Decision:** Remove all 4 existing public methods on `ParallelHelper`. Replace with exactly two:
- `forEach(String executorName, Collection<T> list, Consumer<? super T> consumer, ParallelOptions options)`
- `map(String executorName, List<T> list, Function<? super T, ? extends R> function, ParallelOptions options)`

The executor name is the first parameter. Internally these resolve the executor from the registry and delegate to the private `executeParallel()`.

**Rationale:** Having only one way to specify an executor (by name) enforces the register-first-then-use pattern. No more passing raw executor instances around. The executor name is also naturally forwarded for purge-on-timeout.

### 6. Fail-fast on missing executor

**Decision:** If the registry lookup returns null (not registered), throw `IllegalArgumentException` immediately.

**Rationale:** Fail-fast is better than a cryptic NPE deeper in the stack. Users get a clear message: "No executor registered with name 'xxx'".

### 7. `executeParallel` remains private and unchanged

**Decision:** The private `executeParallel()` method still accepts `ListeningExecutorService executor` and `String executorName` internally. Only the public surface changes.

**Rationale:** Minimizes internal refactoring — the resolution from name to instance happens in the thin public methods, and everything downstream stays the same.

## Risks / Trade-offs

- **[Breaking change]** All existing callers must update. → Mitigation: The project is pre-1.0 (SNAPSHOT), so breaking changes are acceptable. The migration is mechanical: register executor, replace `executor` parameter with `executorName`.
- **[Static mutable state]** The registry is global static state, which makes testing harder. → Mitigation: Consistent with all other `StructuredParallel` state. `unregisterExecutor()` can be used in test teardown.
- **[Name collision]** Two components registering the same name. → Mitigation: `registerExecutor()` allows overwriting (last-write-wins), matching the `setExecutorResolver()` / `setLogger()` pattern.
- **[Unwrap heuristic for ThreadPoolExecutor]** The auto-bridge to `ExecutorResolver` depends on unwrapping the `ListeningExecutorService` to find the underlying `ThreadPoolExecutor`. → Mitigation: Falls back to the explicit `ExecutorResolver` SPI if unwrap fails.
