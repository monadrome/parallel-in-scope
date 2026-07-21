[**English**](README.md) | [**中文**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/HuaTalk/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/HuaTalk/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.huatalk/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.huatalk/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/HuaTalk/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/HuaTalk/parallel-in-scope)](LICENSE)

> 在线文档：[huatalk.github.io/parallel-in-scope](https://huatalk.github.io/parallel-in-scope/)
>
> 当前版本：`v0.1.0`。`0.x` API 仍可能在后续版本中调整。

面向 Java 8+ 的结构化并发工具包，为批量任务提供协作式取消、快速失败、上下文传播、滑动窗口调度和线程池死锁诊断。

## 快速开始

```xml
<dependency>
    <groupId>io.github.huatalk</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.1.0</version>
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

## 核心能力

- 任一任务失败时取消同批任务（Fail-Fast）
- 超时、手动取消和父子任务取消传播
- 有界并发的滑动窗口提交
- `ThreadLocal` 上下文跨线程传播
- CPU / IO 任务类型感知调度
- 任务耗时、排队和异常监控 SPI
- 任务图与执行器图的循环依赖检测

## 文档

| 入口 | 内容 |
|---|---|
| [中文文档中心](docs/zh/index.md) | 使用指南、概念、内部原理、设计与测试文档 |
| [完整使用指南](docs/zh/user-guide.md) | 配置、API、执行时序和进阶功能 |
| [Demo 工程](demo/README.md) | 可运行示例和问题导向文章 |
| [协作式取消](docs/zh/reference/cooperative-cancellation.md) | Checkpoint 与取消传播的 API/契约参考 |

## 兼容性与构建

- 运行时：Java 8+
- 构建工具：Maven 3.x
- 发布产物：根项目 `parallel-in-scope`
- 示例工程：`demo/`，不参与发布

```bash
mvn clean verify
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
```

## License

[Apache License 2.0](LICENSE)
