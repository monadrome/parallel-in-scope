## ADDED Requirements

### Requirement: Immutable ParConfig construction via Builder
`ParConfig` SHALL provide a `public static final class Builder` accessible via `ParConfig.builder()`. The Builder SHALL support fluent chained methods for all configurable aspects. Calling `builder.build()` SHALL return an immutable `ParConfig` instance where all fields are `private final`. The built `ParConfig` SHALL NOT expose any setter or mutator methods.

#### Scenario: Build a ParConfig with defaults
- **WHEN** `ParConfig.builder().build()` is called with no customization
- **THEN** the returned `ParConfig` SHALL have `defaultTimeoutMillis` of 60000, `livelockDetectionEnabled` of false, empty task listener list, empty livelock listener list, no executor resolver, and empty executor registry

#### Scenario: Build a ParConfig with custom timeout
- **WHEN** `ParConfig.builder().defaultTimeoutMillis(30000).build()` is called
- **THEN** the returned `ParConfig` SHALL have `getDefaultTimeoutMillis()` returning 30000

#### Scenario: Build a ParConfig with multiple listeners
- **WHEN** `ParConfig.builder().taskListener(l1).taskListener(l2).build()` is called
- **THEN** `getTaskListeners()` SHALL return an unmodifiable list containing `[l1, l2]` in insertion order

#### Scenario: Built ParConfig is immutable
- **WHEN** a `ParConfig` instance is built and a caller attempts to modify the returned listener list (e.g., `config.getTaskListeners().add(...)`)
- **THEN** an `UnsupportedOperationException` SHALL be thrown

#### Scenario: Null listener rejected
- **WHEN** `ParConfig.builder().taskListener(null)` is called
- **THEN** a `NullPointerException` SHALL be thrown

### Requirement: Builder supports executor registration
`ParConfig.Builder` SHALL provide `executor(String name, ExecutorService executor)` that registers an executor by name. The executor SHALL be adapted to `ListeningExecutorService` via `MoreExecutors.listeningDecorator()` at build time. If `name` is null or empty, `build()` SHALL throw `IllegalArgumentException`. If `executor` is null, `build()` SHALL throw `IllegalArgumentException`.

#### Scenario: Register executor via builder
- **WHEN** `ParConfig.builder().executor("io-pool", threadPoolExecutor).build()` is called
- **THEN** `config.getExecutor("io-pool")` SHALL return a `ListeningExecutorService` wrapping the provided executor

#### Scenario: Register multiple executors via builder
- **WHEN** `ParConfig.builder().executor("io", ioPool).executor("cpu", cpuPool).build()` is called
- **THEN** `config.getExecutor("io")` and `config.getExecutor("cpu")` SHALL each return the corresponding wrapped executor

### Requirement: Builder supports SPI registration
`ParConfig.Builder` SHALL provide:
- `taskListener(TaskListener)` — adds a task lifecycle listener
- `livelockListener(LivelockListener)` — adds a livelock detection listener
- `executorResolver(ExecutorResolver)` — sets the executor resolver

#### Scenario: Register task listener via builder
- **WHEN** `ParConfig.builder().taskListener(listener).build()` is called
- **THEN** `config.getTaskListeners()` SHALL contain the registered listener

#### Scenario: Register executor resolver via builder
- **WHEN** `ParConfig.builder().executorResolver(resolver).build()` is called
- **THEN** `config.getExecutorResolver()` SHALL return the registered resolver

### Requirement: Builder supports livelock detection toggle
`ParConfig.Builder` SHALL provide `livelockDetectionEnabled(boolean)` to enable or disable livelock detection.

#### Scenario: Enable livelock detection via builder
- **WHEN** `ParConfig.builder().livelockDetectionEnabled(true).build()` is called
- **THEN** `config.isLivelockDetectionEnabled()` SHALL return true

### Requirement: Global default instance
`ParConfig` SHALL provide `static ParConfig getDefault()` that returns the current global default instance. The initial global default SHALL be equivalent to `ParConfig.builder().build()` (all defaults). `ParConfig` SHALL provide `static void setDefault(ParConfig config)` to replace the global default. `setDefault` with a null argument SHALL throw `NullPointerException`.

#### Scenario: Get initial default
- **WHEN** no `setDefault` has been called
- **THEN** `ParConfig.getDefault()` SHALL return a ParConfig with default timeout of 60000 and livelock detection disabled

#### Scenario: Override global default
- **WHEN** `ParConfig.setDefault(customConfig)` is called
- **THEN** subsequent calls to `ParConfig.getDefault()` SHALL return `customConfig`

#### Scenario: Set null default
- **WHEN** `ParConfig.setDefault(null)` is called
- **THEN** a `NullPointerException` SHALL be thrown

### Requirement: Remove getInstance
`ParConfig.getInstance()` and the `Holder` inner class SHALL be removed entirely. All global access SHALL use `getDefault()` / `setDefault()`.

#### Scenario: getInstance no longer exists
- **WHEN** code attempts to call `ParConfig.getInstance()`
- **THEN** a compilation error SHALL occur

### Requirement: Par uses global default when no config specified
`new Par()` and `Par.getInstance()` SHALL use `ParConfig.getDefault()` as their configuration. `new Par(ParConfig config)` SHALL continue to accept a custom configuration.

#### Scenario: Par default constructor
- **WHEN** `new Par()` is called
- **THEN** it SHALL use `ParConfig.getDefault()` as its configuration

#### Scenario: Par custom config constructor
- **WHEN** `new Par(customConfig)` is called
- **THEN** it SHALL use `customConfig` as its configuration
