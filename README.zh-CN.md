[**English**](README.md) | [**中文**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.monadrome/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/monadrome/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/monadrome/parallel-in-scope)](LICENSE)

> 当前开发版本：`0.3.0-SNAPSHOT`，已发布到 Central 快照仓库；最新稳定版：`0.2.0`。
> `0.3.0` 对任务组 API 做了破坏性重设计——从 `0.2.x` 升级请先阅读
> [v0.3 迁移指南](docs/zh/migration-v0.3.md)。

面向 Java 8+ 的结构化并发工具包，提供协作式取消、快速失败、上下文传播、滑动窗口调度和线程池死锁诊断。

## 快速开始

以下示例面向 `0.3.0` 线。快照版本不在默认 Central 仓库中，需要先声明快照仓库：

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

在应用启动阶段构建一次执行拓扑，每个逻辑入口绑定它必须使用的执行器：

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

第一次调用前，有两条契约值得知道：

- **超时必须显式二选一。** 批次声明 `BatchOptions.timeout(name, duration)` 或
  `BatchOptions.inheritTimeout(name)`，不存在第三种状态：批次不会因为漏写超时而无界运行，显式超时会被外层
  deadline 封顶。
- **批次默认快速失败。** 第一个元素失败会取消同批其余元素，包括尚未提交的元素。逐元素的结论读
  `TaskFuture.outcome()`（`USER_FAILURE`、`TIMEOUT`、`FAIL_FAST` …），用 `TaskBatchResult.close()`
  释放批次。

`ParRuntime.close()` 只释放框架自建的 timer 与 submitter 服务，不会关闭你注册的执行器。

跨线程上下文传播基于 Alibaba `TransmittableThreadLocal`（TTL）：任务准备时捕获 TTL 的值，在
worker 线程上恢复——普通 `ThreadLocal` 不会跨线程池边界：

```java
TransmittableThreadLocal<String> traceId = new TransmittableThreadLocal<>();
traceId.set("req-42");
// par.map(...) 的 mapper 在线程池线程上读得到 "req-42"
```

仍在使用稳定版 `0.2.0`？它的 API 不同（`GlobalPar` / `ParName`），请参阅
[v0.2.0 使用指南](https://github.com/monadrome/parallel-in-scope/tree/v0.2.0/docs/zh/user-guide.md)。

## 核心能力

- 批次内快速失败取消
- 超时、显式取消与父子级联取消传播
- 有界并发的滑动窗口提交
- 基于 Alibaba `TransmittableThreadLocal`（TTL）的跨线程上下文传播
- CPU / IO 任务感知调度
- 执行、排队与失败的监控 SPI
- 任务图与执行器图的环路检测

## 文档

| 入口 | 内容 |
|---|---|
| [在线文档](https://monadrome.github.io/parallel-in-scope/) | 已发布版本的使用指南 |
| [中文文档](docs/zh/index.md) | 完整中文文档集 |
| [v0.3 迁移指南](docs/zh/migration-v0.3.md) | 相对 `0.2.x` 任务组 API 的破坏性变更 |
| [v0.2 迁移指南](docs/zh/migration-v0.2.md) | 相对 `0.1.x` API 的破坏性变更 |
| [完整使用指南](docs/zh/user-guide.md) | 配置、API 用法、执行流程与高级特性 |
| [English documentation](docs/en/index.md) | 英文文档入口 |
| [示例项目](demo/README.md) | 可运行示例与文章目录 |

## 兼容性与构建

- 运行环境：Java 8+
- 构建工具：Maven 3.x
- 发布构件：仓库根目录的 `parallel-in-scope` 项目
- 示例：独立的 `demo/` 项目，不发布

```bash
mvn clean verify
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
```

## License

[Apache License 2.0](LICENSE)
