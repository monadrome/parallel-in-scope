## MODIFIED Requirements

### Requirement: Package layout
The project SHALL organize all classes into the following packages under the base `io.github.huatalk.parallelinscope`:
- `scope` — public scope API and configuration classes (`Par`, `ParConfig`, `ParOptions`, `AsyncBatchResult`, `TaskType`)
- `cancel` — cancellation and purge classes (`CancellationToken`, `CancellationTokenState`, `Checkpoints`, cancellation exceptions, `HeuristicPurger`)
- `context` — task context relay classes (`TaskScopeTl`, `ThreadRelay`)
- `context.graph` — task graph and livelock graph classes (`TaskGraph`, `TaskEdge`, `TaskEdgeEntry`)
- `internal` — execution internals (`ConcurrentLimitExecutor`, `FutureInspector`, `FutureState`, `ScopedCallable`)
- `queue` — scheduling queue classes
- `spi` — extension point interfaces

Each package SHALL contain a `package-info.java` file with `@javax.annotation.ParametersAreNonnullByDefault` annotation.

#### Scenario: All classes are in their designated packages
- **WHEN** the project compiles successfully
- **THEN** each class resides in its assigned package as defined by the class-to-package mapping

#### Scenario: Each package has package-info.java
- **WHEN** the source directory is listed
- **THEN** each package listed above SHALL contain a `package-info.java` file
