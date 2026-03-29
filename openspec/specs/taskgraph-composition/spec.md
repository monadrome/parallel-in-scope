### Requirement: Composition-based TTL storage
`TaskGraph` SHALL use a composed `TransmittableThreadLocal<TaskGraph.Data>` field instead of extending `TransmittableThreadLocal<TaskGraph.Data>`. The class SHALL NOT appear in the type hierarchy of `TransmittableThreadLocal`.

#### Scenario: TaskGraph is not a TransmittableThreadLocal
- **WHEN** inspecting the class hierarchy of `TaskGraph`
- **THEN** `TaskGraph` SHALL NOT be assignable to `TransmittableThreadLocal`

#### Scenario: TaskGraph is a final class
- **WHEN** inspecting the `TaskGraph` class modifiers
- **THEN** the class SHALL be declared `final`

### Requirement: Preserved TTL semantics
The composed `TransmittableThreadLocal` instance SHALL override `initialValue()` to return `null` and `copy(Data parentValue)` to return `parentValue` (same reference), preserving cross-thread sharing within a request.

#### Scenario: Initial value is null
- **WHEN** a thread accesses the task graph data without prior `initOnRequest()` call
- **THEN** `TaskGraph.data()` SHALL return `null`

#### Scenario: Child thread shares parent's Data
- **WHEN** a parent thread calls `initOnRequest()` and then submits work to a TTL-decorated executor
- **THEN** the child thread's `TaskGraph.data()` SHALL return the same `Data` instance (same object reference) as the parent thread

### Requirement: Unchanged public API
All existing static methods SHALL retain their exact signatures and behavior: `initOnRequest()`, `destroyAfterRequest(ParConfig)`, `logTaskPair(String, String, TaskEdge)`, `data()`, `hasTaskCycle()`, `hasSelfLoop()`, `hasExecutorCycle(ParConfig)`, `hasExecutorSelfLoop(ParConfig)`, `canDeadlock(String, ParConfig)`.

#### Scenario: initOnRequest creates new Data
- **WHEN** `TaskGraph.initOnRequest()` is called
- **THEN** `TaskGraph.data()` SHALL return a non-null `Data` instance

#### Scenario: logTaskPair records edge
- **WHEN** `initOnRequest()` has been called and `logTaskPair("A", "B", edge)` is invoked
- **THEN** the recorded edge SHALL appear in `data().getGraph()`

#### Scenario: destroyAfterRequest cleans up
- **WHEN** `destroyAfterRequest(config)` is called
- **THEN** `TaskGraph.data()` SHALL return `null` afterward
