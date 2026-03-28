## ADDED Requirements

### Requirement: forEach with executor name
`ParallelHelper` SHALL provide exactly one public static method `forEach(String executorName, Collection<T> list, Consumer<? super T> consumer, ParallelOptions options)` that resolves the executor from `StructuredParallel.getExecutor(executorName)` and delegates to the internal parallel execution pipeline. The executor name SHALL also be used for purge service integration.

#### Scenario: Successful forEach by name
- **WHEN** an executor is registered with name "io-pool" and `ParallelHelper.forEach("io-pool", list, consumer, options)` is called
- **THEN** the tasks SHALL execute on the registered executor and the method SHALL return an `AsyncBatchResult`

#### Scenario: forEach with unregistered name
- **WHEN** `ParallelHelper.forEach("unknown", list, consumer, options)` is called and no executor is registered with name "unknown"
- **THEN** an `IllegalArgumentException` SHALL be thrown with a message indicating the executor name is not registered

### Requirement: map with executor name
`ParallelHelper` SHALL provide exactly one public static method `map(String executorName, List<T> list, Function<? super T, ? extends R> function, ParallelOptions options)` that resolves the executor from `StructuredParallel.getExecutor(executorName)` and delegates to the internal parallel execution pipeline. The executor name SHALL also be used for purge service integration.

#### Scenario: Successful map by name
- **WHEN** an executor is registered with name "io-pool" and `ParallelHelper.map("io-pool", list, function, options)` is called
- **THEN** the tasks SHALL execute on the registered executor and the method SHALL return an `AsyncBatchResult`

#### Scenario: map with unregistered name
- **WHEN** `ParallelHelper.map("unknown", list, function, options)` is called and no executor is registered with name "unknown"
- **THEN** an `IllegalArgumentException` SHALL be thrown with a message indicating the executor name is not registered

### Requirement: All previous ParallelHelper overloads removed
All existing `ParallelHelper` public methods that accept `ListeningExecutorService` as a parameter SHALL be removed. The only public methods on `ParallelHelper` SHALL be the two name-based methods defined above.

#### Scenario: No ListeningExecutorService parameter in public API
- **WHEN** the `ParallelHelper` class is inspected
- **THEN** no public method SHALL accept a `ListeningExecutorService` parameter

### Requirement: Name-based methods delegate to existing pipeline
The name-based `map` and `forEach` methods SHALL resolve the executor and then delegate to the existing `executeParallel()` private method, reusing all existing functionality (ScopedCallable wrapping, CancellationToken chaining, sliding-window submission, livelock detection, purge-on-timeout).

#### Scenario: Full pipeline is exercised
- **WHEN** `map("pool", list, fn, opts)` is called
- **THEN** tasks SHALL be wrapped in `ScopedCallable`, submitted via `ConcurrentLimitExecutor`, and benefit from cancellation, timeout, and purge semantics
