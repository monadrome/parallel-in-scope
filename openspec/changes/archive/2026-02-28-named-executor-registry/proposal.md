## Why

Currently, callers must pass a `ListeningExecutorService` instance and an executor name string separately to every `ParallelHelper.map()` / `forEach()` call. This is error-prone (mismatched name vs. instance), verbose, and forces callers to manage executor references at every call site. A central registry standardizes the usage pattern: register executors once at startup, then call `map`/`forEach` with just the executor name.

## What Changes

- Add a named executor registry to `StructuredParallel` that maps `String` name to `ListeningExecutorService`, with register/unregister/lookup APIs.
- Registration wraps the provided `ExecutorService` via `ListeningExecutorAdapter.adapt()` so users can register plain `ExecutorService` or `ThreadPoolExecutor` instances.
- **BREAKING**: Replace all existing `ParallelHelper.map()` and `forEach()` methods with exactly two public methods that accept `String executorName` as the first parameter. All overloads that accept `ListeningExecutorService` directly are removed.
- Unify the existing `ExecutorResolver` SPI with the registry: registered executors automatically become resolvable for purge and livelock detection, eliminating the need for a separate `ExecutorResolver` implementation in common cases.

## Capabilities

### New Capabilities
- `executor-registry`: Central named executor registry — register, lookup, unregister `ListeningExecutorService` by name, with automatic `ExecutorService` adaptation.
- `name-based-parallel-api`: **BREAKING** — Replace all `ParallelHelper` public methods with two name-based methods: `map(String, List, Function, ParallelOptions)` and `forEach(String, Collection, Consumer, ParallelOptions)`. All previous overloads accepting `ListeningExecutorService` are removed.

### Modified Capabilities
(none)

## Impact

- **Code:** `StructuredParallel` gains registry state and methods. `ParallelHelper` public API is replaced entirely. `ListeningExecutorAdapter` is used at registration time.
- **APIs:** **BREAKING** — All existing `ParallelHelper.map()` and `forEach()` signatures are removed. Callers must register executors first, then use the name-based API. New public methods on `StructuredParallel` (register/unregister/get).
- **Dependencies:** No new external dependencies.
- **SPI:** `ExecutorResolver` remains for advanced cases, but the registry can serve as a built-in fallback resolver for purge and livelock detection.
- **Tests:** Existing tests that call `ParallelHelper` with `ListeningExecutorService` must be updated to register executors first and use the name-based API.
