[**English**](README.md) | [**Chinese**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/HuaTalk/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/HuaTalk/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.huatalk/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.huatalk/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/HuaTalk/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/HuaTalk/parallel-in-scope)](LICENSE)

> Current version: `v0.2.0`. APIs may still change in future `0.x` releases.

A structured-concurrency toolkit for Java 8+ with cooperative cancellation, fail-fast execution, context propagation, sliding-window scheduling, and thread-pool deadlock diagnostics.

## Quick Start

```xml
<dependency>
    <groupId>io.github.huatalk</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
ParConfig config = ParConfig.builder()
        .executor("io-pool", Executors.newFixedThreadPool(8))
        .build();

ParOptions options = ParOptions.ioTask("fetch-user")
        .parallelism(4)
        .timeout(3_000)
        .build();

AsyncBatchResult<User> result = new Par(config)
        .map("io-pool", userIds, userService::findById, options);
```

## Core Capabilities

- Fail-fast cancellation within a task batch
- Timeout, explicit, and parent-to-child cancellation propagation
- Sliding-window submission with bounded concurrency
- Cross-thread `ThreadLocal` context propagation
- CPU / IO task-aware scheduling
- Monitoring SPI for execution, queueing, and failures
- Cycle detection across task and executor graphs

## Documentation

| Entry | Contents |
|---|---|
| [English documentation](docs/en/index.md) | User guides and the bilingual documentation map |
| [Full user guide](docs/en/user-guide.md) | Configuration, API usage, execution flow, and advanced features |
| [Demo project](demo/README.en.md) | Runnable examples and the article catalog |
| [Chinese documentation](docs/zh/index.md) | Complete set of current deep-dive documents |

## Compatibility and Build

- Runtime: Java 8+
- Build tool: Maven 3.x
- Published artifact: root `parallel-in-scope` project
- Examples: independent `demo/` project, not published

## License

[Apache License 2.0](LICENSE)
