## Why

The `Par` class currently exposes two parallel execution entry points: `forEach()` and `map()`. Since `forEach` is functionally a subset of `map` (a `Consumer` is just a `Function` that returns `null`), maintaining both methods adds API surface without adding real capability. Removing `forEach` simplifies the API to a single entry point, reducing cognitive load for users and maintenance burden.

## What Changes

- **BREAKING**: Remove the `Par.forEach()` public method entirely
- Migrate all existing `forEach` call sites (tests and demos) to use `Par.map()` with a function that returns `null`
- Update or remove tests that specifically test `forEach` behavior (the underlying execution pipeline is already covered by `map` tests)

## Capabilities

### New Capabilities

_(none — this is a removal/simplification change)_

### Modified Capabilities

- `name-based-parallel-api`: The `forEach` method is removed from the `Par` API, leaving `map` as the sole parallel execution entry point. This is a requirement-level change to the public API surface.

## Impact

- **Public API**: `Par.forEach(String, List<T>, Consumer<? super T>, ParOptions)` is removed. This is a **breaking change** for any downstream consumers using `forEach`.
- **Test files**: 7 test methods across `ParTest.java` and `ExecutorRegistryTest.java` reference `forEach` and must be migrated to `map`.
- **Demo applications**: 2 demo classes (`NestedScopeCancellationDemo`, `DeadlockDetectionDemo`) call `forEach` and must be updated.
- **No runtime behavior change**: The execution pipeline (`executeParallel`) is shared between `forEach` and `map` — removing `forEach` does not alter any runtime semantics.
