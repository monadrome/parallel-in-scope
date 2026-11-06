[**English**](README.md) | [**中文**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.monadrome/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/monadrome/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/monadrome/parallel-in-scope)](LICENSE)
[![交互式演示](https://img.shields.io/badge/interactive--demo-live-blueviolet)](https://monadrome.github.io/parallel-in-scope/interactive-demo.html)

> 最新稳定版：`0.4.0`。本版本新增类型化任务 key、请求级取消、在途 future 收养和运行时诊断。
> 从 `0.3.x` 升级请先阅读 [v0.4 迁移指南](docs/zh/migration-v0.4.md)。

面向 Java 8+ 的结构化并发工具包，提供协作式取消、快速失败、上下文传播、滑动窗口调度和线程池死锁与饥饿诊断。

## 环境要求

| 用途 | 要求 |
|---|---|
| 运行 | Java 8 或更高版本 |
| 构建 | JDK 25 LTS（编译期由 Error Prone/NullAway 强制检查） |
| 构建工具 | Maven 3.9.x |

发布构件仍是 Java 8 字节码。JDK 21 会被 enforcer 拒绝，运行 Maven 前请切换到 JDK 25。

## 最小示例

从 Maven Central 添加依赖：

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.4.0</version>
</dependency>
```

下面是一个最小完整程序：一个执行器、一个批次和一个显式超时：

```java
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class QuickStart {

    public static void main(String[] args) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("io"), pool)
                .defaultPar(ParId.of("io"))
                .build();
        try {
            Par io = runtime.defaultPar();
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4);
            TaskBatchResult<Integer> squares = io.map(
                    numbers,
                    n -> n * n,
                    BatchOptions.timeout("squares", Duration.ofSeconds(5)).parallelism(2));
            System.out.println("SUCCESS:" + squares.valuesOrThrow());
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
```

预期输出为 `SUCCESS:[1, 4, 9, 16]`。`map` 是同步调用，会阻塞调用线程；调用线程被中断不会取消批次。
超时必须显式选择 `timeout(...)` 或 `inheritTimeout(...)`。批次快速失败：首个失败会取消其余元素，包括尚未提交的元素。

`Par.map` 接收 `Function`，受检异常必须包一层，例如使用 `java.io.UncheckedIOException`。在 `0.3.x` 中，
`valuesOrThrow()` 抛受检的 `ExecutionException`；`0.4.0` 改为非受检失败。

第一次调用前，有三条契约值得知道：

- **超时必须显式二选一。** 批次声明 `BatchOptions.timeout(name, duration)` 或
  `BatchOptions.inheritTimeout(name)`，不存在第三种状态：批次不会因为漏写超时而无界运行，显式超时会被外层
  deadline 封顶。
- **批次默认快速失败。** 第一个元素失败会取消同批其余元素，包括尚未提交的元素。逐元素的结论读
  `ImmediateResult.outcome()`（`USER_FAILURE`、`TIMEOUT`、`FAIL_FAST` …），执行同步返回，结果无需 close；释放直接任务共享的资源前检查
  `executionExitConfirmed()`，嵌套任务须分别确认退出。
- **parallelism 默认不设额外上限。** `BatchOptions` 缺省即 `Integer.MAX_VALUE`，解析时被任务总数
  封顶——不写 `.parallelism(...)` 等于整批一次性提交。需要有界窗口就显式设置；0 或负数在入口
  被拒绝。

固定的一组异构命名操作——取用户、取账户，再组装档案——改用一次性任务组而不是批次。`0.4.0`
发布版使用可复用的类型化 key：

```java
TaskKey<User> user = TaskKey.named("user");
TaskKey<Account> account = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");
TaskGroupResult<Tuple2<User, Account>, Profile> profile = runtime
        .group("profile", Duration.ofSeconds(3))
        .par(user, io, () -> loadUser(userId))
        .par(account, io, () -> loadAccount(userId))
        .combine(profileKey, io,
                values -> Profile.of(values.first(), values.second()))
        .runAll();
Profile value = profile.terminalValueOrThrow();
```

`runAll()` 一次性消费声明并返回冻结结果，没有需要关闭的句柄。首个成员失败会取消其余成员，
成员超时则取消整个组。批次的聚合读取在 0.4.0 中改为非受检，组的聚合读取在 0.3.x 本就是非受检；参见
[v0.4 迁移指南](https://github.com/monadrome/parallel-in-scope/blob/v0.4.0/docs/zh/migration-v0.4.md)。

仍在使用稳定版 `0.2.0`？它的 API 不同（`GlobalPar` / `ParName`），请参阅
[v0.2.0 使用指南](https://github.com/monadrome/parallel-in-scope/tree/v0.2.0/docs/zh/user-guide.md)。

## 核心能力

- 批次内快速失败取消
- 超时、显式取消与父子级联取消传播
- 有界并发的滑动窗口提交
- 基于 Alibaba `TransmittableThreadLocal`（TTL）的跨线程上下文传播
- 显式选择的入队拒绝（`rejectEnqueue` 配合 `SmartBlockingQueue`）与负载匹配诊断（`TaskType` 发现进了计算池的阻塞任务，从不改变执行）
- 终态任务观测：`completions()` / 组 `members()` 与 `terminal()`
- 任务图与执行器图的环路检测

## 文档

| 入口 | 内容 |
|---|---|
| [在线文档](https://monadrome.github.io/parallel-in-scope/) | 已发布版本的使用指南 |
| [交互式演示](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) | 单文件动画演示四条核心语义 |
| [稳定版中文文档](https://monadrome.github.io/parallel-in-scope/zh/) | `0.4.0` 发布线指南 |
| [v0.4 文档源码](https://github.com/monadrome/parallel-in-scope/tree/v0.4.0/docs/zh) | 发布版冻结源码 |
| [0.4 中文文档](https://github.com/monadrome/parallel-in-scope/tree/v0.4.0/docs/zh) | `0.4.0` 发布线指南 |
| [English documentation](https://monadrome.github.io/parallel-in-scope/) | 英文稳定版入口 |
| [示例项目](https://github.com/monadrome/parallel-in-scope/tree/v0.4.0/demo) | 可运行示例与文章目录 |
| [集成示例](https://github.com/monadrome/parallel-in-scope/tree/v0.4.0/integrations) | 0.4.0：Resilience4j、Sentinel、Spring 独立示例 |

## 兼容性与构建

- 运行环境：Java 8+
- 构建 JDK：25（LTS）——编译期运行 Error Prone/NullAway，产物仍为 Java 8 字节码
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
