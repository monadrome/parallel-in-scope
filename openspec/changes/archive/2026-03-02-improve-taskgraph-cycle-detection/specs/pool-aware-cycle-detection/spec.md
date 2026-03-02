## ADDED Requirements

### Requirement: Executor deadlock risk assessment
`TaskGraph` SHALL assess each executor's deadlock risk based on its `ThreadPoolExecutor` configuration. An executor SHALL be considered deadlock-prone only when it has both a bounded `maximumPoolSize` (< `Integer.MAX_VALUE`) and a queue that is not `SynchronousQueue`.

#### Scenario: CachedThreadPool self-loop is safe
- **WHEN** a task running on a CachedThreadPool (SynchronousQueue + MAX_VALUE maximumPoolSize) forks child tasks onto the same CachedThreadPool
- **THEN** `hasExecutorSelfLoop()` SHALL return `false`

#### Scenario: FixedThreadPool self-loop is risky
- **WHEN** a task running on a FixedThreadPool (LinkedBlockingQueue + bounded maximumPoolSize) forks child tasks onto the same FixedThreadPool
- **THEN** `hasExecutorSelfLoop()` SHALL return `true`

#### Scenario: Unknown executor is treated conservatively
- **WHEN** `Par.resolveThreadPool(executorName)` returns `null` for an executor involved in a cycle or self-loop
- **THEN** the executor SHALL be treated as deadlock-prone (`canDeadlock() = true`)

### Requirement: Executor graph built from edge metadata
`TaskGraph` SHALL build the executor-level graph directly from `TaskEdge` metadata (both `sourceExecutorName` and `executorName` fields) without requiring `ExecutorResolver.getTaskToExecutorMapping()`.

#### Scenario: Executor graph without ExecutorResolver
- **WHEN** no `ExecutorResolver` is registered and tasks are submitted with named executors
- **THEN** the executor-level graph SHALL correctly reflect executor dependencies using the executor names recorded in `TaskEdge`

#### Scenario: Source executor name propagation
- **WHEN** a task running on executor "pool-A" forks child tasks onto executor "pool-B"
- **THEN** the resulting `TaskEdge` SHALL have `sourceExecutorName = "pool-A"` and `executorName = "pool-B"`

#### Scenario: Top-level caller has no executor
- **WHEN** the initial caller (not running in a Par-managed thread) forks child tasks onto executor "pool-A"
- **THEN** the resulting `TaskEdge` SHALL have `sourceExecutorName = "NA"`

### Requirement: Multi-edge preservation
`TaskGraph` SHALL preserve all edges between the same (parent, child) task pair. When the same parent task forks the same child task multiple times within a request, all `TaskEdge` instances SHALL be retained.

#### Scenario: Duplicate parent-child pairs
- **WHEN** task "A" forks task "B" twice with different parallelism settings (e.g., p=4 then p=2)
- **THEN** the task-level graph SHALL contain both `TaskEdge` instances for the A→B edge

#### Scenario: Cycle detection with multi-edges
- **WHEN** task "A" forks task "B" and task "B" forks task "A"
- **THEN** `hasTaskCycle()` SHALL return `true` regardless of how many edges exist between A and B

### Requirement: Filtered executor cycle detection
Executor-level cycle and self-loop detection SHALL only report issues involving deadlock-prone executors. Edges targeting non-deadlock-prone executors SHALL be excluded from cycle/self-loop analysis.

#### Scenario: Mixed pool types in cycle
- **WHEN** executor "fixed-pool" (FixedThreadPool) and executor "cached-pool" (CachedThreadPool) form a cycle, and the edge targets "cached-pool"
- **THEN** the cycle SHALL NOT be reported if the only path through the cycle involves a non-deadlock-prone executor as a target

#### Scenario: All deadlock-prone cycle
- **WHEN** executor "pool-A" (FixedThreadPool) and executor "pool-B" (FixedThreadPool) form a cycle
- **THEN** `hasExecutorCycle()` SHALL return `true`
