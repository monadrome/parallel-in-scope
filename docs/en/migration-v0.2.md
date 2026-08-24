# Migrating to v0.2

Version `0.2.0` replaces the mutable configuration-and-resolver API with an immutable execution topology. This is a source-breaking migration.

| `0.1.x` | `0.2.0` |
|---|---|
| `ParConfig.builder().executor(name, executor)` | `GlobalPar.builder().register(name, executor)` |
| `new Par(config)` | `global.par(name)` |
| `ParOptions` | `ExecutionOptions` |
| `par.map(name, items, fn, options)` | `par.map(items, fn, options)` |
| `ParConfig` timeout/listener defaults | `GlobalExecutionPolicy` |
| `ParConfig` livelock settings | `GlobalParLivelockPolicy` |
| `ParConfig` purge settings | `GlobalParPurgePolicy` |
| executor-name resolution at call time | executor binding at `GlobalPar` build time |
| `TaskGraph.destroyAfterRequest(config)` | `global.openObservation()` scope |

The new split is intentional: `ExecutionOptions` is caller input, while `BatchExecutionContext` is per-batch runtime state. Cancellation, deadline, and executor identity flow through parent-child batch contexts, including nested calls across named `Par` entries.

The old `ParConfig`, `ParOptions`, `ExecutorResolver`, `GlobalParConfig`, and legacy `Par` entry points are not compatibility aliases. Update imports, construction, and method calls together. Registered executors remain borrowed and are still owned and shut down by the application.
