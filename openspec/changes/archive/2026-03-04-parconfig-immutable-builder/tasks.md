## 1. ParConfig Builder & Immutability

- [x] 1.1 Add `ParConfig.Builder` static inner class with fluent methods: `defaultTimeoutMillis(long)`, `livelockDetectionEnabled(boolean)`, `taskListener(TaskListener)`, `livelockListener(LivelockListener)`, `executorResolver(ExecutorResolver)`, `executor(String, ExecutorService)`
- [x] 1.2 Add `build()` method that constructs an immutable `ParConfig` with `private final` fields, unmodifiable collections, and validation (null/empty executor name, null executor)
- [x] 1.3 Add `ParConfig.builder()` static factory method returning a new `Builder`
- [x] 1.4 Change `ParConfig` constructor to `private ParConfig(Builder builder)`, remove the public no-arg constructor
- [x] 1.5 Remove all setter/mutator methods from `ParConfig`: `setDefaultTimeoutMillis`, `setLivelockDetectionEnabled`, `setExecutorResolver`, `addTaskListener`, `removeTaskListener`, `addLivelockListener`, `removeLivelockListener`, `registerExecutor`, `unregisterExecutor`
- [x] 1.6 Make `getTaskListeners()` and `getLivelockListeners()` return unmodifiable lists

## 2. Global Default Instance

- [x] 2.1 Remove `Holder` inner class and `getInstance()` method entirely
- [x] 2.2 Replace with `AtomicReference<ParConfig> DEFAULT` initialized to `new Builder().build()`
- [x] 2.3 Add `static ParConfig getDefault()` and `static void setDefault(ParConfig)` (null check → NPE)

## 3. Update Par Facade

- [x] 3.1 Update `Par` default constructor and `Par.getInstance()` to use `ParConfig.getDefault()`
- [x] 3.2 Verify `Par(ParConfig config)` constructor still works with custom configs

## 4. Update Consumers

- [x] 4.1 Update `Scoped/Callable` — verify read-only usage of `config.getTaskListeners()` (no code change expected)
- [x] 4.2 Update `TaskGraph` — verify read-only usage of `config.isLivelockDetectionEnabled()`, `getLivelockListeners()`, `resolveThreadPool()` (no code change expected)
- [x] 4.3 Update `HeuristicPurger` — verify read-only usage of `config.resolveThreadPool()` (no code change expected)

## 5. Migrate Tests

- [x] 5.1 Migrate `ParTest` — replace `new ParConfig()` + setter calls with `ParConfig.builder()...build()`
- [x] 5.2 Migrate `ExecutorRegistryTest` — replace `registerExecutor`/`unregisterExecutor` calls with builder-based setup
- [x] 5.3 Migrate `TaskGraphTest` — replace mutable config setup with builder
- [x] 5.4 Migrate `ScopedCallableTest` — replace mutable config setup with builder
- [x] 5.5 Migrate `HeuristicPurgerTest` — replace mutable config setup with builder

## 6. Verification

- [x] 6.1 Run `mvn clean compile` — verify zero compilation errors
- [x] 6.2 Run `mvn test` — verify all tests pass (119 tests, 0 failures)
- [x] 6.3 Verify immutability: confirm `getTaskListeners().add(...)` throws `UnsupportedOperationException` (guaranteed by Guava `ImmutableList`)

## 7. Update README & Demos

- [x] 7.1 Update README — replace all `ParConfig` usage examples with builder pattern, update API documentation sections
- [x] 7.2 Update `BasicDemo` — migrate to `ParConfig.builder()...build()`
- [x] 7.3 Update `CancellationDemo` — migrate to builder pattern
- [x] 7.4 Update `DeadlockDetectionDemo` — migrate to builder pattern
- [x] 7.5 Update `NestedScopeCancellationDemo` — migrate to builder pattern
