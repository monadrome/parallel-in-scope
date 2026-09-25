[**English**](README.md) | [**中文**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.monadrome/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/monadrome/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/monadrome/parallel-in-scope)](LICENSE)

> Current development version: `0.3.0-SNAPSHOT`, published to the Central snapshot repository.
> Latest stable release: `0.2.0`. The `0.3.0` line is a breaking redesign of the task-group API —
> migrating from `0.2.x`? Read the [v0.3 migration guide](docs/en/migration-v0.3.md) first.

A structured-concurrency toolkit for Java 8+ with cooperative cancellation, fail-fast execution,
context propagation, sliding-window scheduling, and thread-pool deadlock diagnostics.

## Quick Start

The snippets below target the `0.3.0` line. Snapshots are not served by the default Central
repository, so declare the snapshot repository next to the dependency:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.3.0-SNAPSHOT</version>
</dependency>
```

Register every logical entry with the executor it must use, once at the composition root:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), Executors.newFixedThreadPool(8))
        .build();

BatchOptions options = BatchOptions.timeout("fetch-user", Duration.ofSeconds(3))
        .parallelism(4)
        .taskType(TaskType.IO_BOUND);

TaskBatchResult<User> result = runtime.par(ParId.of("io"))
        .map(userIds, userService::findById, options);

for (TaskFuture<User> future : result.results()) {
    System.out.println(future.taskName() + " -> " + future.outcome());
}
```

Two contracts shape that first call:

- **The timeout is a forced choice.** A batch declares either `BatchOptions.timeout(name, duration)`
  or `BatchOptions.inheritTimeout(name)`; there is no third state, so a batch cannot run unbounded
  by omission, and an explicit timeout is capped by any enclosing deadline.
- **A batch is fail-fast.** The first failure cancels the rest of the batch, including elements that
  were never submitted. Read `TaskFuture.outcome()` for the per-element verdict (`USER_FAILURE`,
  `TIMEOUT`, `FAIL_FAST`, …) and close the `TaskBatchResult` to release the batch.

`ParRuntime.close()` releases the framework-owned timer and submitter services; it never shuts down
the executors you registered.

Cross-thread context propagation runs on Alibaba `TransmittableThreadLocal` (TTL): a TTL value is
captured when the task is prepared and restored on the worker thread — a plain `ThreadLocal` does
not cross the pool boundary:

```java
TransmittableThreadLocal<String> traceId = new TransmittableThreadLocal<>();
traceId.set("req-42");
// the mapper inside par.map(...) reads "req-42" on the pool thread
```

Staying on the stable `0.2.0` line? Its API is different (`GlobalPar` / `ParName`); use the
[v0.2.0 user guide](https://github.com/monadrome/parallel-in-scope/tree/v0.2.0/docs/en/user-guide.md).

## Core Capabilities

- Fail-fast cancellation within a task batch
- Timeout, explicit, and parent-to-child cancellation propagation
- Sliding-window submission with bounded concurrency
- Cross-thread context propagation via Alibaba `TransmittableThreadLocal` (TTL)
- CPU / IO task-aware scheduling
- Monitoring SPI for execution, queueing, and failures
- Cycle detection across task and executor graphs

## Documentation

| Entry | Contents |
|---|---|
| [Online documentation](https://monadrome.github.io/parallel-in-scope/) | Published guide for the released line |
| [English documentation](docs/en/index.md) | User guide, API contracts, design notes, and case studies |
| [v0.3 migration guide](docs/en/migration-v0.3.md) | Breaking changes from the `0.2.x` task-group API |
| [v0.2 migration guide](docs/en/migration-v0.2.md) | Breaking changes from the `0.1.x` API |
| [Full user guide](docs/en/user-guide.md) | Configuration, API usage, execution flow, and advanced features |
| [Demo project](demo/README.en.md) | Runnable examples and the article catalog |
| [Chinese documentation](docs/zh/index.md) | Complete Chinese documentation set |

## Compatibility and Build

- Runtime: Java 8+
- Build tool: Maven 3.x
- Published artifact: root `parallel-in-scope` project
- Examples: independent `demo/` project, not published

```bash
mvn clean verify
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
```

## License

[Apache License 2.0](LICENSE)
