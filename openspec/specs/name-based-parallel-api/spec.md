## ADDED Requirements

### Requirement: map with executor name
`Par` SHALL provide exactly one public method `map(String executorName, List<T> list, Function<? super T, ? extends R> function, ParOptions options)` that resolves the executor from `ParConfig.getExecutor(executorName)` and delegates to the internal parallel execution pipeline. The executor name SHALL also be used for purge service integration.

#### Scenario: Successful map by name
- **WHEN** an executor is registered with name "io-pool" and `par.map("io-pool", list, function, options)` is called
- **THEN** the tasks SHALL execute on the registered executor and the method SHALL return an `AsyncBatchResult`

#### Scenario: map with unregistered name
- **WHEN** `par.map("unknown", list, function, options)` is called and no executor is registered with name "unknown"
- **THEN** an `IllegalArgumentException` SHALL be thrown with a message indicating the executor name is not registered

### Requirement: All previous ParallelHelper overloads removed
All existing `Par` public methods that accept `ListeningExecutorService` as a parameter SHALL be removed. The only public method on `Par` SHALL be the name-based `map` method defined above. The `forEach` method SHALL also be removed.

#### Scenario: No ListeningExecutorService parameter in public API
- **WHEN** the `Par` class is inspected
- **THEN** no public method SHALL accept a `ListeningExecutorService` parameter

#### Scenario: No forEach method in public API
- **WHEN** the `Par` class is inspected
- **THEN** no public method named `forEach` SHALL exist

### Requirement: Name-based methods delegate to existing pipeline
The name-based `map` method SHALL resolve the executor and then delegate to the existing `executeParallel()` private method, reusing all existing functionality (ScopedCallable wrapping, CancellationToken chaining, sliding-window submission, livelock detection, purge-on-timeout).

#### Scenario: Full pipeline is exercised
- **WHEN** `map("pool", list, fn, opts)` is called
- **THEN** tasks SHALL be wrapped in `ScopedCallable`, submitted via `ConcurrentLimitExecutor`, and benefit from cancellation, timeout, and purge semantics
