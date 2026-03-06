## ADDED Requirements

### Requirement: Internal classes have package-private visibility
Classes that are pure internal implementation details SHALL be declared with package-private (default) visibility instead of `public`.

#### Scenario: ThreadRelay is package-private
- **WHEN** code outside `io.github.huatalk.vformation` attempts to reference `ThreadRelay`
- **THEN** it SHALL fail to compile because `ThreadRelay` has package-private visibility

#### Scenario: ThreadRelay.RelayItem is package-private
- **WHEN** code outside the package attempts to reference `ThreadRelay.RelayItem`
- **THEN** it SHALL fail to compile because both `ThreadRelay` and `RelayItem` have package-private visibility

#### Scenario: ConcurrentLimitExecutor is package-private
- **WHEN** code outside the package attempts to reference `ConcurrentLimitExecutor`
- **THEN** it SHALL fail to compile because `ConcurrentLimitExecutor` has package-private visibility

#### Scenario: TaskEdgeEntry is package-private
- **WHEN** code outside the package attempts to reference `TaskEdgeEntry`
- **THEN** it SHALL fail to compile because `TaskEdgeEntry` has package-private visibility

#### Scenario: SlidingWindowCounter remains package-private
- **WHEN** `SlidingWindowCounter` is inspected
- **THEN** it SHALL remain package-private (already was before this change)

### Requirement: Internal methods have package-private visibility
Methods that serve only internal cross-class wiring SHALL be declared with package-private (default) visibility.

#### Scenario: ParOptions internal methods
- **WHEN** code outside the package attempts to call `ParOptions.timeoutMillis()` or `ParOptions.forTimeout()`
- **THEN** it SHALL fail to compile because these methods have package-private visibility

#### Scenario: ParConfig internal infrastructure
- **WHEN** code outside the package attempts to call `ParConfig.getTimer()` or `ParConfig.getSubmitterPool()`
- **THEN** it SHALL fail to compile because these methods have package-private visibility

#### Scenario: AsyncBatchResult factory methods
- **WHEN** code outside the package attempts to call `AsyncBatchResult.of(submitCanceller, results)` or `AsyncBatchResult.of(results)`
- **THEN** it SHALL fail to compile because these factory methods have package-private visibility

#### Scenario: CancellationToken internal methods
- **WHEN** code outside the package attempts to call `CancellationToken.lateBind(...)` or construct `new CancellationToken(parent)`
- **THEN** it SHALL fail to compile because `lateBind` and the parent-constructor have package-private visibility

#### Scenario: CancellationTokenState internal method
- **WHEN** code outside the package attempts to call `CancellationTokenState.shouldInterruptCurrentThread()`
- **THEN** it SHALL fail to compile because this method has package-private visibility

#### Scenario: HeuristicPurger internal method
- **WHEN** code outside the package attempts to call `HeuristicPurger.tryPurge(...)`
- **THEN** it SHALL fail to compile because this method has package-private visibility

#### Scenario: TaskScopeTl setter methods
- **WHEN** code outside the package attempts to call `TaskScopeTl.setCancellationToken()`, `setParallelOptions()`, `init()`, or `remove()`
- **THEN** it SHALL fail to compile because these methods have package-private visibility

#### Scenario: TaskScopeTl getter methods remain public
- **WHEN** code outside the package calls `TaskScopeTl.getCancellationToken()` or `TaskScopeTl.getParallelOptions()`
- **THEN** it SHALL compile successfully because these getter methods remain `public`

#### Scenario: TaskGraph internal methods
- **WHEN** code outside the package attempts to call `TaskGraph.logTaskPair(...)` or `TaskGraph.canDeadlock(...)`
- **THEN** it SHALL fail to compile because these methods have package-private visibility

#### Scenario: ScopedCallable constructors and setters
- **WHEN** code outside the package attempts to construct `new ScopedCallable(...)` or call `setParallelOptions()`, `setCancellationToken()`, `setExecutorName()`
- **THEN** it SHALL fail to compile because constructors and setters have package-private visibility

#### Scenario: ScopedCallable.current() and getters remain public
- **WHEN** code outside the package calls `ScopedCallable.current()`, `getParallelOptions()`, `getCancellationToken()`, or `getExecutorName()`
- **THEN** it SHALL compile successfully because these methods remain `public`

### Requirement: Unused methods removed
Methods with zero call sites SHALL be deleted.

#### Scenario: ThreadRelay.clearCurrentTaskName() removed
- **WHEN** the codebase is searched for `clearCurrentTaskName`
- **THEN** no declaration or call site SHALL exist

#### Scenario: ThreadRelay.getParentParallelOptions() removed
- **WHEN** the codebase is searched for `getParentParallelOptions`
- **THEN** no declaration or call site SHALL exist

#### Scenario: ThreadRelay.getParentTaskName() removed
- **WHEN** the codebase is searched for `getParentTaskName`
- **THEN** no declaration or call site SHALL exist

### Requirement: Public API surface preserved for user-facing classes
All classes and methods categorized as MUST or SHOULD in the API audit SHALL retain `public` visibility.

#### Scenario: Par remains fully public
- **WHEN** `Par` class and all its public methods are inspected
- **THEN** they SHALL have `public` visibility

#### Scenario: SPI interfaces remain fully public
- **WHEN** `TaskListener`, `ExecutorResolver`, `LivelockListener`, `PurgeStrategy` and their inner types are inspected
- **THEN** they SHALL all have `public` visibility

#### Scenario: Checkpoints remains fully public
- **WHEN** `Checkpoints` class and all its public methods are inspected
- **THEN** they SHALL have `public` visibility

### Requirement: Full test suite passes after access level changes
The access level changes SHALL NOT alter any runtime behavior.

#### Scenario: All tests pass
- **WHEN** `mvn test` is run after all access level changes
- **THEN** all tests SHALL pass with the same results as before

#### Scenario: Demo module compiles
- **WHEN** `mvn compile` is run on the full project
- **THEN** the `vformation-demo` module SHALL compile successfully (no references to now-internal APIs)
