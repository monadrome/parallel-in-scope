## ADDED Requirements

### Requirement: Package layout
The project SHALL organize all classes into the following 7 packages under the base `io.github.linzee1.concurrent`:
- `scope` — API facade classes
- `context` — TTL/TL context propagation classes
- `context.graph` — Livelock detection classes
- `cancel` — Cancellation subsystem classes
- `internal` — Execution engine and utility classes
- `queue` — Scheduling queue classes
- `spi` — Extension point interfaces

#### Scenario: All classes are in their designated packages
- **WHEN** the project compiles successfully
- **THEN** each class resides in its assigned package as defined by the class-to-package mapping

### Requirement: Class-to-package mapping
The system SHALL place classes into packages as follows:

**scope:** ParallelHelper, ParallelOptions, AsyncBatchResult, TaskType, StructuredParallel
**context:** ThreadRelay, TaskScopeTl
**context.graph:** TaskGraph, TaskEdge, TaskEdgeEntry
**cancel:** CancellationToken, CancellationTokenState, Checkpoints, FatCancellationException, LeanCancellationException, PurgeService
**internal:** ConcurrentLimitExecutor, ScopedCallable, Attachable, FutureInspector, ListeningExecutorAdapter
**queue:** SmartBlockingQueue, VariableLinkedBlockingQueue
**spi:** TaskListener, ExecutorResolver, LivelockListener, ParallelLogger

#### Scenario: No class remains in the old package
- **WHEN** the refactoring is complete
- **THEN** no `.java` file SHALL have package declaration `io.github.linzee1.parallel` or `io.github.linzee1.parallel.exception`

### Requirement: All classes are public
All classes and interfaces SHALL have `public` visibility after the refactoring.

#### Scenario: Previously package-private classes become public
- **WHEN** classes ScopedCallable, ThreadRelay, Attachable, TaskGraph, TaskEdge, TaskEdgeEntry are moved to new packages
- **THEN** each SHALL have the `public` access modifier

### Requirement: Maven coordinates updated
The Maven artifactId SHALL be `structured-concurrency` and the project name SHALL reflect "Structured Concurrency".

#### Scenario: pom.xml reflects new identity
- **WHEN** the pom.xml is read
- **THEN** the artifactId SHALL be `structured-concurrency`

### Requirement: Old packages deleted
The old package directories `io.github.linzee1.parallel` and `io.github.linzee1.parallel.exception` SHALL be completely removed after all classes are migrated.

#### Scenario: No residual files in old locations
- **WHEN** migration is complete
- **THEN** the directory `src/main/java/io/github/linzee1/parallel/` SHALL not exist

### Requirement: Tests updated
All test files SHALL update their package declarations and imports to reference the new package structure, and all tests SHALL pass after the refactoring.

#### Scenario: All tests pass
- **WHEN** `mvn test` is executed after refactoring
- **THEN** all existing tests SHALL pass with zero failures
