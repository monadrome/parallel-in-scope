## MODIFIED Requirements

### Requirement: Register executor by name
`ParConfig.Builder` SHALL provide `executor(String name, ExecutorService executor)` that stores the executor in the builder's registry, keyed by the given name. The executor SHALL be adapted to `ListeningExecutorService` via `MoreExecutors.listeningDecorator()` at build time. If `name` is null or empty, `build()` SHALL throw `IllegalArgumentException`. If `executor` is null, `build()` SHALL throw `IllegalArgumentException`. The runtime mutator methods `registerExecutor()` and `unregisterExecutor()` on `ParConfig` SHALL be removed.

#### Scenario: Register a plain ThreadPoolExecutor via builder
- **WHEN** `ParConfig.builder().executor("io-pool", threadPoolExecutor).build()` is called with a valid `ThreadPoolExecutor`
- **THEN** the executor SHALL be stored and retrievable via `config.getExecutor("io-pool")` as a `ListeningExecutorService`

#### Scenario: Register with null name
- **WHEN** `ParConfig.builder().executor(null, executor).build()` is called
- **THEN** an `IllegalArgumentException` SHALL be thrown

#### Scenario: Register with null executor
- **WHEN** `ParConfig.builder().executor("pool", null).build()` is called
- **THEN** an `IllegalArgumentException` SHALL be thrown

### Requirement: Retrieve executor by name
`ParConfig` SHALL provide an instance method `getExecutor(String name)` that returns the `ListeningExecutorService` registered under the given name, or `null` if no executor is registered with that name.

#### Scenario: Retrieve a registered executor
- **WHEN** an executor has been registered with name "io-pool" via the builder
- **THEN** `config.getExecutor("io-pool")` SHALL return the adapted `ListeningExecutorService`

#### Scenario: Retrieve an unregistered name
- **WHEN** no executor has been registered with name "unknown"
- **THEN** `config.getExecutor("unknown")` SHALL return `null`

## REMOVED Requirements

### Requirement: Unregister executor by name
**Reason**: `ParConfig` is now immutable. Runtime mutation of the executor registry is no longer supported. Executors are registered at build time via the Builder.
**Migration**: Use `ParConfig.builder().executor(name, executor).build()` to configure executors. To change the configuration, build a new `ParConfig` instance.

### Requirement: Thread-safe registry operations
**Reason**: Thread safety is now guaranteed by immutability. All collections in `ParConfig` are unmodifiable after construction. No concurrent mutation is possible, so explicit thread-safety of mutation operations is no longer applicable.
**Migration**: No migration needed. Thread safety is inherently guaranteed by the immutable design.

### Requirement: Auto-bridge to purge subsystem
`ParConfig.resolveThreadPool(String)` SHALL check the `ExecutorResolver` first, then fall back to the builder-registered raw executor registry. This behavior is unchanged, but the underlying storage is now an immutable map populated at build time.

#### Scenario: Resolve ThreadPoolExecutor from builder-registered executor
- **WHEN** a `ThreadPoolExecutor` is registered via `builder.executor("cpu-pool", tpe)` and no `ExecutorResolver` is set
- **THEN** `config.resolveThreadPool("cpu-pool")` SHALL return the original `ThreadPoolExecutor`

#### Scenario: Explicit ExecutorResolver takes priority
- **WHEN** a `ThreadPoolExecutor` is registered via builder with name "cpu-pool" AND an `ExecutorResolver` is set via `builder.executorResolver(resolver)` that returns a different `ThreadPoolExecutor` for "cpu-pool"
- **THEN** `config.resolveThreadPool("cpu-pool")` SHALL return the one from the `ExecutorResolver`
