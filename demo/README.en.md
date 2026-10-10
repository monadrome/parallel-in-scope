[**Chinese**](README.md) | **English**

# parallel-in-scope-demo

An independent consumer project containing runnable examples for `parallel-in-scope`.

> The demo targets the current `0.3.0` API (published as `0.3.0-SNAPSHOT`) and serves as an external-consumer reference.

## Build and Run

Install the library from the repository root, then build the demo:

```bash
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
mvn -f demo/pom.xml exec:java
```

The demo resolves `io.github.monadrome:parallel-in-scope` at the version pinned by
`parallel-in-scope.version` in its `pom.xml`. Override it on the command line with
`-Dparallel-in-scope.version=<version>`; CI passes the version it just built from the
repository root, so the demo verifies the current source tree instead of a possibly stale
pinned default.

Run a specific example:

```bash
mvn -f demo/pom.xml exec:java -Dexec.mainClass=demo.basic.BasicParDemo
mvn -f demo/pom.xml exec:java -Dexec.mainClass=demo.basic.CancellationDemo
mvn -f demo/pom.xml exec:java -Dexec.mainClass=demo.advanced.DeadlockDetectionDemo
mvn -f demo/pom.xml exec:java -Dexec.mainClass=demo.integration.BatchProcessingDemo
```

## Architecture Boundary

The demo depends on the published library artifact and acts as an external consumer:

```text
demo -> io.github.monadrome:parallel-in-scope
```

Examples use the `demo.*` namespace and access only public API types from the root package (`ParRuntime`, `Par`, `BatchOptions`, `TaskBatchResult`, `TaskGroupResult`, `ImmediateResult`, `GroupStart`, `GroupStep`, `GroupValues`, `Tuple2`) and the `queue` package. Execution is synchronous; existing Guava consumer examples explicitly adapt completed results with `asFuture()`. Cancellation, context, graph, and scheduling internals are package-private in the root package.

## Documentation

- [English documentation map](docs/en/README.md)
- [Chinese article catalog](docs/zh-CN/README.md)
- [Architecture constraints](architecture-constraints.md) (Chinese)
- [Project documentation](../docs/en/index.md)
