## Why

`ParConfig` is the only major mutable configuration class in the vformation framework. All other config/value objects (`ParOptions`, `TaskEdge`, `AsyncBatchResult`) are immutable. The current mutable design with `volatile` fields and setter methods creates thread-safety risks: concurrent mutation of the singleton can lead to inconsistent state visible across threads (e.g., listeners added mid-execution, timeout changed between normalization and use). A builder-based immutable `ParConfig` aligns with the existing `ParOptions.Builder` pattern, provides a global default, and eliminates these risks.

## What Changes

- **Add `ParConfig.Builder`**: a fluent builder for constructing `ParConfig` instances, following the same pattern as `ParOptions.Builder`.
- **Make `ParConfig` immutable**: all fields become `private final`; remove all setter/mutator methods (`setDefaultTimeoutMillis`, `setLivelockDetectionEnabled`, `setExecutorResolver`, `addTaskListener`, `removeTaskListener`, `addLivelockListener`, `removeLivelockListener`, `registerExecutor`, `unregisterExecutor`). **BREAKING**
- **Add global default instance**: `ParConfig.getDefault()` returns a pre-built default instance. `ParConfig.setDefault(ParConfig)` allows one-time or test-time override of the global default. The lazy singleton `getInstance()` is replaced by this mechanism.
- **Provide `ParConfig.builder()` static factory**: returns a new `Builder` pre-populated with default values.
- **Update `Par` and all consumers**: replace mutable `ParConfig` usage with builder-based construction. `new Par()` / `Par.getInstance()` will use `ParConfig.getDefault()`.

## Capabilities

### New Capabilities
- `parconfig-builder`: Builder pattern for immutable ParConfig construction, with global default instance management.

### Modified Capabilities
- `executor-registry`: Executor registration moves from mutable runtime methods to builder-time configuration. The `registerExecutor`/`unregisterExecutor` mutator API is removed in favor of `Builder.executor(name, executorService)`.

## Impact

- **Breaking API change**: All `ParConfig` setter methods are removed. Users who call `config.addTaskListener(...)`, `config.registerExecutor(...)`, `config.setDefaultTimeoutMillis(...)`, etc. must migrate to builder-based construction.
- **Affected code**: `ParConfig`, `Par`, `ScopedCallable`, `TaskGraph`, `HeuristicPurger`, all test classes, all demo classes.
- **No new dependencies**: Uses existing Java 8 / Guava primitives only.
