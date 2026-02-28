## 1. Create new directory structure

- [x] 1.1 Create package directories under `src/main/java/io/github/linzee1/concurrent/`: `scope`, `context`, `context/graph`, `cancel`, `internal`, `queue`, `spi`
- [x] 1.2 Create test package directories under `src/test/java/io/github/linzee1/concurrent/`

## 2. Move and update SPI interfaces (zero internal deps — move first)

- [x] 2.1 Move `TaskListener`, `ExecutorResolver`, `LivelockListener`, `ParallelLogger` to `concurrent.spi`, update package declarations

## 3. Move and update cancel package

- [x] 3.1 Move `CancellationTokenState` to `concurrent.cancel` (zero deps), update package declaration
- [x] 3.2 Move `FatCancellationException`, `LeanCancellationException` from `parallel.exception` to `concurrent.cancel`, update package declarations
- [x] 3.3 Move `CancellationToken` to `concurrent.cancel`, update package declaration and imports
- [x] 3.4 Move `Checkpoints` to `concurrent.cancel`, update package declaration and imports
- [x] 3.5 Move `PurgeService` to `concurrent.cancel`, update package declaration and imports

## 4. Move and update context package

- [x] 4.1 Move `TaskScopeTl` to `concurrent.context`, update package declaration and imports
- [x] 4.2 Move `ThreadRelay` to `concurrent.context`, update package declaration and imports
- [x] 4.3 Move `TaskEdge` to `concurrent.context.graph`, update package declaration and imports
- [x] 4.4 Move `TaskEdgeEntry` to `concurrent.context.graph`, update package declaration and imports
- [x] 4.5 Move `TaskGraph` to `concurrent.context.graph`, update package declaration and imports, make class `public`

## 5. Move and update internal package

- [x] 5.1 Move `Attachable` to `concurrent.internal`, update package declaration, make `public`
- [x] 5.2 Move `FutureInspector` to `concurrent.internal`, update package declaration
- [x] 5.3 Move `ListeningExecutorAdapter` to `concurrent.internal`, update package declaration
- [x] 5.4 Move `ScopedCallable` to `concurrent.internal`, update package declaration and imports, make class `public`
- [x] 5.5 Move `ConcurrentLimitExecutor` to `concurrent.internal`, update package declaration and imports

## 6. Move and update queue package

- [x] 6.1 Move `VariableLinkedBlockingQueue` to `concurrent.queue`, update package declaration
- [x] 6.2 Move `SmartBlockingQueue` to `concurrent.queue`, update package declaration and imports

## 7. Move and update scope package (API facade)

- [x] 7.1 Move `TaskType` to `concurrent.scope`, update package declaration
- [x] 7.2 Move `ParallelOptions` to `concurrent.scope`, update package declaration and imports
- [x] 7.3 Move `AsyncBatchResult` to `concurrent.scope`, update package declaration and imports
- [x] 7.4 Move `StructuredParallel` to `concurrent.scope`, update package declaration and imports
- [x] 7.5 Move `ParallelHelper` to `concurrent.scope`, update package declaration and imports

## 8. Update visibility

- [x] 8.1 Make `ScopedCallable`, `ThreadRelay`, `Attachable`, `TaskGraph`, `TaskEdge`, `TaskEdgeEntry` all `public`

## 9. Update test files

- [x] 9.1 Move `ParallelHelperTest` to `concurrent` test package, update package and imports
- [x] 9.2 Move `CancellationTokenTest` to `concurrent` test package, update package and imports
- [x] 9.3 Move `CheckpointsTest` to `concurrent` test package, update package and imports
- [x] 9.4 Move `FutureInspectorTest` to `concurrent` test package, update package and imports
- [x] 9.5 Move `SmartBlockingQueueTest` to `concurrent` test package, update package and imports
- [x] 9.6 Move `ParallelOptionsTest` to `concurrent` test package, update package and imports

## 10. Update project metadata

- [x] 10.1 Update `pom.xml`: artifactId to `structured-concurrency`, update name and description
- [x] 10.2 Update `CLAUDE.md` to reflect new package structure and project name

## 11. Cleanup and verify

- [x] 11.1 Delete old directory tree `src/main/java/io/github/linzee1/parallel/`
- [x] 11.2 Delete old test directory `src/test/java/io/github/linzee1/parallel/`
- [x] 11.3 Run `mvn clean compile` — verify zero compilation errors
- [x] 11.4 Run `mvn test` — verify all tests pass
