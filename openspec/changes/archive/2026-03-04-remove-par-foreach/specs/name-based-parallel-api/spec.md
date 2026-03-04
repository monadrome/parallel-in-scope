## REMOVED Requirements

### Requirement: forEach with executor name
**Reason**: The `forEach` method is functionally redundant with `map`. A `Consumer` can be expressed as a `Function` that returns `null`. Removing `forEach` simplifies the API to a single entry point.
**Migration**: Replace `par.forEach(executorName, list, consumer, options)` with `par.map(executorName, list, item -> { consumer.accept(item); return null; }, options)`.

## MODIFIED Requirements

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
