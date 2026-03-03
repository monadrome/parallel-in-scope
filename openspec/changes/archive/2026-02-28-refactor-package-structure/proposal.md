## Why

The project's root package `io.github.huatalk.vformation` contains 19 classes with mixed responsibilities — API facade, execution engine, cancellation mechanism, livelock detection, and utilities all flat in one package. This makes it hard for users to understand the API surface and for maintainers to reason about module boundaries. The project name `vformation` also doesn't accurately reflect the structured concurrency paradigm the library implements.

## What Changes

- **BREAKING**: Rename base package from `io.github.huatalk.vformation` to `io.github.huatalk.vformation`
- **BREAKING**: Rename Maven artifactId from `vformation` to `vformation`
- Reorganize 27 classes/interfaces into 7 domain-oriented sub-packages:
  - `scope` — API facade (ParallelHelper, ParallelOptions, AsyncBatchResult, TaskType, StructuredParallel)
  - `context` — TTL/TL context propagation (ThreadRelay, TaskScopeTl)
  - `context.graph` — Livelock detection (TaskGraph, TaskEdge, TaskEdgeEntry)
  - `cancel` — Cancellation subsystem (CancellationToken, CancellationTokenState, Checkpoints, FatCancellationException, LeanCancellationException, PurgeService)
  - `internal` — Execution engine + utilities (ConcurrentLimitExecutor, ScopedCallable, Attachable, FutureInspector, ListeningExecutorAdapter)
  - `queue` — Scheduling queues (SmartBlockingQueue, VariableLinkedBlockingQueue)
  - `spi` — Extension points (TaskListener, ExecutorResolver, LivelockListener, ParallelLogger)
- Delete the old `exception` sub-package (contents merged into `cancel`)
- Make all classes `public` initially; optimize visibility in a follow-up

## Capabilities

### New Capabilities
- `package-structure`: Defines the package layout, class-to-package mapping, and inter-package dependency rules for the reorganized codebase

### Modified Capabilities

(none — this is a pure structural refactor with no behavioral changes)

## Impact

- **All source files**: Every `.java` file needs package declaration and import updates
- **All test files**: Same package/import updates
- **pom.xml**: artifactId, name, description changes
- **CLAUDE.md**: Architecture documentation update
- **Directory structure**: New directory tree under `src/main/java/io/github/huatalk/concurrent/`
- **Downstream consumers**: Any existing users must update all imports (breaking change)
