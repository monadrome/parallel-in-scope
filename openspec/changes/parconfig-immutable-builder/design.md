## Context

`ParConfig` is the central configuration and service registry for the vformation framework. Currently it is mutable—fields use `volatile`, collections use `CopyOnWriteArrayList` / `ConcurrentHashMap`, and the class exposes setter methods (`setDefaultTimeoutMillis`, `addTaskListener`, `registerExecutor`, etc.). A lazy-holder singleton (`Holder.INSTANCE`) provides a shared global instance.

Every other configuration/value class in the project (`ParOptions`, `TaskEdge`, `AsyncBatchResult`) is immutable. `ParOptions` already uses a builder pattern. `ParConfig` is the outlier.

Consumers of `ParConfig`:
- `Par` — holds a `ParConfig` reference, reads executors, default timeout, passes to `ScopedCallable`
- `ScopedCallable` — reads task listeners
- `TaskGraph` — reads livelock settings, resolves thread pools
- `HeuristicPurger` — resolves thread pools
- `CancellationToken` — uses `ParConfig.getTimer()` (static, unchanged)

## Goals / Non-Goals

**Goals:**
- Make `ParConfig` immutable after construction: all fields `private final`, no setters.
- Provide `ParConfig.Builder` with fluent API consistent with `ParOptions.Builder`.
- Provide a global default instance (`ParConfig.getDefault()`) that users can override once at application startup.
- Ensure thread safety through immutability rather than `volatile` / concurrent collections.
- Keep Java 8 compatibility; no new dependencies.

**Non-Goals:**
- Dynamic runtime reconfiguration of `ParConfig`. Once built, it is fixed.
- Changing the static global infrastructure (`getTimer()`, `getSubmitterPool()`). These remain class-level singletons.
- Merging `ParConfig` and `ParOptions`—they serve different scopes (application-level vs per-invocation).
- Supporting "partial override" / layered configs. A single flat builder is sufficient.

## Decisions

### 1. Builder as static inner class of ParConfig

**Decision**: `ParConfig.Builder` is a `public static final class` inside `ParConfig`, following the same pattern as `ParOptions.Builder`.

**Rationale**: Keeps builder co-located with the product. Users already know this pattern from `ParOptions`. No new top-level class needed.

**Alternatives considered**:
- Lombok `@Builder` — rejected because the project uses Java 8 and Lombok is provided-scope only; explicit builder is clearer and more controllable.
- Constructor with many parameters — rejected because there are 5+ configurable aspects; builder is more readable.

### 2. Immutable collections via Guava `ImmutableList` / `ImmutableMap`

**Decision**: Builder accumulates listeners in `ImmutableList.Builder`. `ParConfig` constructor stores `builder.taskListeners.build()` as `ImmutableList<TaskListener>`. Same pattern for livelock listeners.

**Rationale**: Guava is already a core dependency (33.2.1-jre). `ImmutableList` is truly immutable (not a wrapper over a mutable list), rejects nulls at insertion time, and is more memory-efficient. The `ImmutableList.Builder` pattern integrates naturally with the `ParConfig.Builder` — each `taskListener(l)` call just does `taskListenersBuilder.add(l)`.

### 3. Executor registry as Guava `ImmutableMap`

**Decision**: Builder accumulates executor registrations in a `LinkedHashMap`. The `ParConfig` constructor builds both the decorated (`ListeningExecutorService`) map and the raw (`ExecutorService`) map as `ImmutableMap` instances via `ImmutableMap.copyOf()`.

**Rationale**: Same reasoning as decision #2 — `ImmutableMap` is truly immutable, null-hostile, and consistent with the `ImmutableList` choice for listeners. `MoreExecutors.listeningDecorator()` is called at build time, exactly as `registerExecutor()` does today.

### 4. Global default via `AtomicReference<ParConfig>`

**Decision**: Replace the `Holder` lazy singleton with:
```java
private static final AtomicReference<ParConfig> DEFAULT = new AtomicReference<>(new ParConfig.Builder().build());

public static ParConfig getDefault() { return DEFAULT.get(); }
public static void setDefault(ParConfig config) { DEFAULT.set(config); }
```

**Rationale**: `AtomicReference` provides thread-safe read/write without synchronization. `setDefault` is intended for application bootstrap or test setup. The initial default is a zero-config `ParConfig` built with all defaults (60s timeout, no listeners, no executors, livelock detection off).

**Alternatives considered**:
- Keep `Holder` pattern with `volatile` override field — more complex, same effect.
- `compareAndSet` to enforce single-write — too restrictive for test scenarios where setup/teardown calls `setDefault` repeatedly.

### 5. Remove `getInstance()` entirely

**Decision**: `getInstance()` and the `Holder` inner class are deleted. `getDefault()` / `setDefault()` is the only global access API.

**Rationale**: The project is pre-1.0 (`1.0.0-SNAPSHOT`). Breaking the API now is safe and avoids carrying dead weight. A clean API is more valuable than backward compatibility at this stage.

### 6. `Par` default constructor uses `ParConfig.getDefault()`

**Decision**: `new Par()` and `Par.getInstance()` both use `ParConfig.getDefault()`. `new Par(config)` continues to accept a custom `ParConfig`.

**Rationale**: Most users want the global default. Custom configs are for tests and multi-tenant scenarios.

## Risks / Trade-offs

- **[Breaking API]** All `ParConfig` setter methods are removed. → **Mitigation**: Every setter has a direct builder equivalent. Migration is mechanical. Deprecation warnings guide users.
- **[No runtime reconfiguration]** Users cannot add listeners or executors after `ParConfig` is built. → **Mitigation**: This is intentional. Runtime mutation was a source of thread-safety bugs. Users who need dynamic behavior can rebuild and call `setDefault()`, or use `ExecutorResolver` SPI which is a single immutable reference.
- **[Test boilerplate increase]** Tests that previously did `config.registerExecutor(...)` inline must now use a builder chain. → **Mitigation**: The builder's fluent API is concise; test setup is typically 3-4 chained calls. A convenience method `ParConfig.builder().executor(name, es).build()` covers the common case.
- **[`setDefault` is not atomic with reads]** A thread calling `getDefault()` may see the old config while another thread calls `setDefault()`. → **Mitigation**: This is the same visibility guarantee as the old `volatile` singleton. `setDefault` is designed for bootstrap, not hot-swapping.
