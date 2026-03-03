## 1. Executor Registry on StructuredParallel

- [x] 1.1 Add `ConcurrentHashMap<String, ListeningExecutorService>` static field `EXECUTOR_REGISTRY` to `StructuredParallel`
- [x] 1.2 Add `registerExecutor(String name, ExecutorService executor)` method with null/empty validation and `ListeningExecutorAdapter.adapt()` wrapping
- [x] 1.3 Add `getExecutor(String name)` method returning `ListeningExecutorService` or null
- [x] 1.4 Add `unregisterExecutor(String name)` method (no-op if absent)

## 2. Auto-bridge to Purge Subsystem

- [x] 2.1 Modify `resolveThreadPool(String)` in `StructuredParallel` to fall back to the registry when no explicit `ExecutorResolver` is set — unwrap `ListeningExecutorService` to find the underlying `ThreadPoolExecutor`

## 3. Replace ParallelHelper Public API (BREAKING)

- [x] 3.1 Remove all 4 existing public methods on `ParallelHelper` (the overloads accepting `ListeningExecutorService`)
- [x] 3.2 Add `forEach(String executorName, Collection<T> list, Consumer<? super T> consumer, ParallelOptions options)` that resolves executor from registry and delegates to `executeParallel()`
- [x] 3.3 Add `map(String executorName, List<T> list, Function<? super T, ? extends R> function, ParallelOptions options)` that resolves executor from registry and delegates to `executeParallel()`
- [x] 3.4 Add private `resolveExecutor(String executorName)` helper that calls `StructuredParallel.getExecutor()` and throws `IllegalArgumentException` if null

## 4. Update Existing Tests

- [x] 4.1 Update all existing tests that call `ParallelHelper.map()`/`forEach()` with `ListeningExecutorService` to register executors first and use the name-based API

## 5. New Tests

- [x] 5.1 Test register/get/unregister lifecycle (register, retrieve, unregister, verify null)
- [x] 5.2 Test register with null name and null executor throws `IllegalArgumentException`
- [x] 5.3 Test `map` and `forEach` with executor name — verify tasks execute correctly
- [x] 5.4 Test `map` and `forEach` with unregistered name — verify `IllegalArgumentException`
- [x] 5.5 Test auto-bridge: register a `ThreadPoolExecutor`, verify `resolveThreadPool()` returns it without explicit `ExecutorResolver`
- [x] 5.6 Test explicit `ExecutorResolver` takes priority over registry
- [x] 5.7 Run full test suite (`mvn test`) to verify no regressions
