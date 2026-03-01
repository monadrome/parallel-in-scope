## ADDED Requirements

### Requirement: Register executor by name
`StructuredParallel` SHALL provide a static method `registerExecutor(String name, ExecutorService executor)` that stores the executor in a central registry, keyed by the given name. The executor SHALL be adapted to `ListeningExecutorService` via `ListeningExecutorAdapter.adapt()` at registration time. If `name` is null or empty, the method SHALL throw `IllegalArgumentException`. If `executor` is null, the method SHALL throw `IllegalArgumentException`.

#### Scenario: Register a plain ThreadPoolExecutor
- **WHEN** `StructuredParallel.registerExecutor("io-pool", threadPoolExecutor)` is called with a valid `ThreadPoolExecutor`
- **THEN** the executor SHALL be stored and retrievable via `StructuredParallel.getExecutor("io-pool")` as a `ListeningExecutorService`

#### Scenario: Register with null name
- **WHEN** `StructuredParallel.registerExecutor(null, executor)` is called
- **THEN** an `IllegalArgumentException` SHALL be thrown

#### Scenario: Register with null executor
- **WHEN** `StructuredParallel.registerExecutor("pool", null)` is called
- **THEN** an `IllegalArgumentException` SHALL be thrown

### Requirement: Retrieve executor by name
`StructuredParallel` SHALL provide a static method `getExecutor(String name)` that returns the `ListeningExecutorService` registered under the given name, or `null` if no executor is registered with that name.

#### Scenario: Retrieve a registered executor
- **WHEN** an executor has been registered with name "io-pool"
- **THEN** `StructuredParallel.getExecutor("io-pool")` SHALL return the adapted `ListeningExecutorService`

#### Scenario: Retrieve an unregistered name
- **WHEN** no executor has been registered with name "unknown"
- **THEN** `StructuredParallel.getExecutor("unknown")` SHALL return `null`

### Requirement: Unregister executor by name
`StructuredParallel` SHALL provide a static method `unregisterExecutor(String name)` that removes the executor registered under the given name. If no executor is registered with that name, the method SHALL be a no-op.

#### Scenario: Unregister an existing executor
- **WHEN** an executor is registered with name "io-pool" and `StructuredParallel.unregisterExecutor("io-pool")` is called
- **THEN** `StructuredParallel.getExecutor("io-pool")` SHALL return `null`

#### Scenario: Unregister a non-existent name
- **WHEN** `StructuredParallel.unregisterExecutor("nonexistent")` is called
- **THEN** no exception SHALL be thrown

### Requirement: Thread-safe registry operations
All registry operations (register, unregister, get) SHALL be thread-safe. Concurrent registrations and lookups SHALL not cause data corruption or exceptions.

#### Scenario: Concurrent register and get
- **WHEN** multiple threads concurrently register and retrieve executors
- **THEN** each `getExecutor()` call SHALL return either the registered executor or `null`, never a corrupted value

### Requirement: Auto-bridge to purge subsystem
When an executor is registered via `registerExecutor()` and the underlying executor is a `ThreadPoolExecutor`, `StructuredParallel.resolveThreadPool(name)` SHALL return that `ThreadPoolExecutor` even if no explicit `ExecutorResolver` is set. If an explicit `ExecutorResolver` is set, it SHALL take priority over the registry.

#### Scenario: Resolve ThreadPoolExecutor from registry
- **WHEN** a `ThreadPoolExecutor` is registered with name "cpu-pool" and no `ExecutorResolver` is set
- **THEN** `StructuredParallel.resolveThreadPool("cpu-pool")` SHALL return the original `ThreadPoolExecutor`

#### Scenario: Explicit ExecutorResolver takes priority
- **WHEN** a `ThreadPoolExecutor` is registered with name "cpu-pool" AND an `ExecutorResolver` is set that returns a different `ThreadPoolExecutor` for "cpu-pool"
- **THEN** `StructuredParallel.resolveThreadPool("cpu-pool")` SHALL return the one from the `ExecutorResolver`
