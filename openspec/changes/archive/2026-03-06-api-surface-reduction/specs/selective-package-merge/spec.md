## ADDED Requirements

### Requirement: Tightly-coupled packages merged to root package
The 5 tightly-coupled sub-packages (`scope`, `cancel`, `context`, `context.graph`, `internal`) SHALL be merged into the root package `io.github.huatalk.vformation`.

#### Scenario: Source files from scope package
- **WHEN** any source file previously in `io.github.huatalk.vformation.scope` (Par, ParOptions, ParConfig, AsyncBatchResult, TaskType) is compiled
- **THEN** its package declaration SHALL be `package io.github.huatalk.vformation;`

#### Scenario: Source files from cancel package
- **WHEN** any source file previously in `io.github.huatalk.vformation.cancel` (CancellationToken, CancellationTokenState, Checkpoints, FatCancellationException, LeanCancellationException, HeuristicPurger, SlidingWindowCounter) is compiled
- **THEN** its package declaration SHALL be `package io.github.huatalk.vformation;`

#### Scenario: Source files from context package
- **WHEN** any source file previously in `io.github.huatalk.vformation.context` (ThreadRelay, TaskScopeTl) is compiled
- **THEN** its package declaration SHALL be `package io.github.huatalk.vformation;`

#### Scenario: Source files from context.graph package
- **WHEN** any source file previously in `io.github.huatalk.vformation.context.graph` (TaskGraph, TaskEdge, TaskEdgeEntry) is compiled
- **THEN** its package declaration SHALL be `package io.github.huatalk.vformation;`

#### Scenario: Source files from internal package
- **WHEN** any source file previously in `io.github.huatalk.vformation.internal` (ScopedCallable, ConcurrentLimitExecutor, FutureInspector, FutureState) is compiled
- **THEN** its package declaration SHALL be `package io.github.huatalk.vformation;`

### Requirement: Independent packages remain unchanged
The `queue` and `spi` packages SHALL retain their original package declarations and directory locations.

#### Scenario: queue package unchanged
- **WHEN** SmartBlockingQueue.java or VariableLinkedBlockingQueue.java is inspected
- **THEN** its package declaration SHALL remain `package io.github.huatalk.vformation.queue;`

#### Scenario: spi package unchanged
- **WHEN** TaskListener.java, ExecutorResolver.java, LivelockListener.java, or PurgeStrategy.java is inspected
- **THEN** its package declaration SHALL remain `package io.github.huatalk.vformation.spi;`

### Requirement: Intra-package imports removed for merged classes
After merging, import statements between formerly separate sub-packages SHALL be removed (same-package classes do not need import statements).

#### Scenario: Cross-sub-package imports eliminated
- **WHEN** a merged source file previously imported a class from another merged sub-package (e.g., `scope.Par` importing `internal.ScopedCallable`)
- **THEN** that import statement SHALL be removed

#### Scenario: External imports preserved
- **WHEN** a source file imports from external libraries (Guava, TTL, JDK)
- **THEN** those import statements SHALL remain unchanged

### Requirement: Independent packages update their imports
The `queue` and `spi` packages SHALL update import statements that reference merged packages to use the new root package path.

#### Scenario: SmartBlockingQueue import update
- **WHEN** SmartBlockingQueue.java imports TaskScopeTl (previously `io.github.huatalk.vformation.context.TaskScopeTl`)
- **THEN** the import SHALL be updated to `io.github.huatalk.vformation.TaskScopeTl`

#### Scenario: SmartBlockingQueue other imports
- **WHEN** SmartBlockingQueue.java imports ParOptions and TaskType (previously from `scope`)
- **THEN** imports SHALL be updated to `io.github.huatalk.vformation.ParOptions` and `io.github.huatalk.vformation.TaskType`

#### Scenario: spi package needs no import updates
- **WHEN** any spi source file is inspected
- **THEN** it SHALL have zero imports from any vformation package (spi is a pure leaf)

### Requirement: Merged sub-package directories removed
After merging, the old sub-package directories SHALL NOT exist.

#### Scenario: Sub-package directories cleaned up
- **WHEN** the restructure is complete
- **THEN** the directories `scope/`, `cancel/`, `context/`, `context/graph/`, `internal/` under the base package SHALL NOT exist

#### Scenario: Independent package directories preserved
- **WHEN** the restructure is complete
- **THEN** the directories `queue/` and `spi/` under the base package SHALL still exist with their original files

### Requirement: Test imports updated
All test source files SHALL update their import statements for classes that moved to the root package.

#### Scenario: Test compilation succeeds
- **WHEN** `mvn test-compile` is run after the restructure
- **THEN** compilation SHALL succeed with zero errors

### Requirement: Demo module imports updated
All source files in `vformation-demo` module SHALL update imports for classes that moved to the root package. Imports from `spi` package remain unchanged.

#### Scenario: Demo compilation succeeds
- **WHEN** `mvn compile` is run on the full project after the restructure
- **THEN** the `vformation-demo` module SHALL compile with zero errors

#### Scenario: Demo spi imports unchanged
- **WHEN** DeadlockDetectionDemo.java imports `LivelockListener` from spi
- **THEN** the import SHALL remain `io.github.huatalk.vformation.spi.LivelockListener`

### Requirement: Full test suite passes after restructure
The restructure SHALL NOT alter any runtime behavior.

#### Scenario: All tests pass
- **WHEN** `mvn test` is run after the restructure
- **THEN** all existing tests SHALL pass with the same results as before the restructure
