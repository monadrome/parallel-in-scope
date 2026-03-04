## Why

`TaskGraph` currently extends `TransmittableThreadLocal<TaskGraph.Data>` directly, coupling the livelock-detection domain logic (graph building, cycle detection, listener notification) with the thread-local storage mechanism. This inheritance makes the class harder to test in isolation, violates single-responsibility principle, and prevents swapping the storage strategy without modifying the graph logic.

## What Changes

- **Replace inheritance with composition**: `TaskGraph` will no longer extend `TransmittableThreadLocal`. Instead, it will hold a `TransmittableThreadLocal<TaskGraph.Data>` field internally and delegate `get()`/`set()`/`remove()` calls to it.
- **Override `initialValue()` and `copy()` on the composed TTL instance** (via anonymous subclass) to preserve existing behavior: `initialValue()` returns `null`, `copy()` returns the same parent reference.
- **Preserve the public API surface**: All static methods (`initOnRequest()`, `destroyAfterRequest()`, `logTaskPair()`, `data()`, `hasTaskCycle()`, `hasSelfLoop()`, `hasExecutorCycle()`, `hasExecutorSelfLoop()`) remain unchanged. No call-site changes needed.
- **Inner class `Data` stays unchanged**: The `Data` class with its `LinkedTransferQueue<TaskEdgeEntry>`, lazy graph generation, and cycle-detection logic is not affected.

## Capabilities

### New Capabilities

- `taskgraph-composition`: Refactored `TaskGraph` using composition over inheritance for the `TransmittableThreadLocal` storage mechanism, decoupling graph logic from thread-local lifecycle.

### Modified Capabilities

- `pool-aware-cycle-detection`: No requirement-level changes — all existing cycle/self-loop detection behavior is preserved. Implementation details change (storage access via delegation instead of inheritance) but spec-level behavior is identical.

## Impact

- **Code**: Only `TaskGraph.java` needs modification. The singleton `TTL` field changes from `this` (the class instance) to an internal `TransmittableThreadLocal<Data>` field.
- **APIs**: No public API changes. All static methods retain their signatures.
- **Tests**: Existing `TaskGraphTest`, `ParTest`, and `ExecutorRegistryTest` should pass without modification — they only use the public static API.
- **Dependencies**: No new dependencies. Still uses `TransmittableThreadLocal` from Alibaba TTL library.
