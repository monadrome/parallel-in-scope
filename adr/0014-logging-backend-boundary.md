# ADR 0014: 核心日志保持 JUL，后端接入由应用负责

- Status: Accepted
- Date: 2026-11-08
- Decision scope: 核心库的日志 API、日志后端依赖与应用侧集成边界
- Supersedes: None

## Context

parallel-in-scope 是被嵌入应用的 Java 并发库。它当前直接使用 JDK 的
`java.util.logging`（JUL），并已经把若干日志行为写入公开文档与 Javadoc：运行时诊断在
runtime-owned executor 上异步投递，准入线程不调用用户 handler，日志失败不能改变任务结果，日志
只是 best effort，可靠诊断事实通过报告 API 获取。

用户可能已经使用 Logback、Log4j2、SLF4J 或其他日志系统。直接把具体日志实现带入库会造成
依赖冲突并覆盖用户配置；把日志 API 改成 SLF4J 则会新增一个传递依赖，并要求同步修改日志测试、
文档和当前的可观察契约。

调研了 Guava、gRPC-Java、HikariCP、ZooKeeper、Curator、Kafka、RabbitMQ Java Client、Jedis、
Lettuce、Netty、Reactor、Tomcat、Jetty、Dubbo、Caffeine、OkHttp 和 Jackson 等组件。基础库没有
统一路线：有的直接使用 JUL 或 `System.Logger`，有的使用 SLF4J facade，有的提供自有适配层，
还有的对专项诊断使用显式 logger 注入。SLF4J 在 Java 库生态中很普遍，但并不意味着每个零依赖
基础库都应采用它。

## Decision

核心库继续使用 JUL，不在当前开发线中新增 `slf4j-api`、Logback、Log4j2、Commons Logging 或其他
具体日志后端依赖。

应用侧可以通过 JUL 配置或 bridge（例如 JUL 到 SLF4J 的适配器）把库日志汇聚到自己的日志系统。
库不安装、初始化或重配置应用的日志后端，也不携带任何具体 provider。

现有日志行为继续有效：

1. 准入线程不得直接调用用户可替换的日志 handler。
2. 日志投递或 handler 失败不得改变任务、取消或清理结果。
3. 日志是 best effort；需要可靠读取的诊断信息必须通过报告或观测 API 提供。
4. 日志类别使用类或机制的稳定 logger name，用户可以通过 JUL 配置路由或屏蔽记录。

如果未来决定改用日志 facade，应以新的 ADR 明确取代本决定，并优先评估只依赖 `slf4j-api`、不
携带具体 provider 的方案；迁移必须同时更新公开文档、Javadoc、测试捕获方式和日志失败隔离语义。

## Alternatives Considered

### 选项 A：核心库迁移到 SLF4J

SLF4J 能让用户在部署时选择 Logback、Log4j2、JUL 或 NOP，生态集成优于直接使用 JUL。否决当前
迁移是因为它会新增传递依赖，改变无 provider 时的默认行为，并要求同步调整现有日志契约与大量
JUL `Handler`/`LogRecord` 测试。该方案保留为未来生态统一需求出现时的后继 ADR 候选。

### 选项 B：引入自有 logger facade 或 provider SPI

Netty、Reactor 和 Dubbo 证明了自有适配层可以同时支持多种后端。否决是因为本项目需要维护
provider 发现、优先级、类加载器、运行时切换和失败语义；这些复杂度超过当前日志需求，且会增加
新的公共或半公共扩展边界。

### 选项 C：直接依赖 Logback、Log4j2 或 Commons Logging

这些方案可以提供更丰富的实现能力或自动发现。否决是因为会把具体生态和配置冲突传递给嵌入应用，
与基础库不替用户决定日志后端的定位冲突。

### 选项 D：核心库不记录日志，只提供诊断 API

Jackson 核心组件展示了这种低依赖路线。否决为当前默认方案是因为本项目已有周期性风险和迟到
失败等 best-effort 提醒，完全删除日志会降低运行时发现问题的便利性；可靠事实仍必须由报告 API
承担。

## Consequences

正面：核心保持零日志框架依赖；Java 8、JRE 和受限运行环境的兼容性不受影响；不会强制用户选择
provider，也不会因为多个日志框架共存而引入新的类路径冲突；现有异步投递、失败隔离和测试策略
继续适用。

代价：用户若要把日志统一接入 SLF4J、Logback 或 Log4j2，需要在应用侧配置 bridge；JUL 的 API
能力和上下文模型比现代日志框架有限；库的日志调用仍然表现为 JUL 记录。

风险与边界：bridge 的安装顺序、重复安装和日志环路由应用负责。库不得依赖 bridge 的存在，也
不得把日志后端不可用视为任务失败。日志格式、滚动、采集和敏感字段脱敏属于应用日志系统的责任。

## Reconsideration

只有在下列情况出现时，才重开本决定并新增后继 ADR：

- 目标生态明确要求库原生暴露 SLF4J logger，且应用侧 bridge 已成为普遍部署负担；
- JUL 的级别、上下文或结构化事件能力无法表达新的公开诊断契约；
- 需要支持一个现有 JUL 无法满足的运行时日志隔离或 provider 选择场景。

重开时必须重新评估传递依赖、无 provider 行为、Java 8 兼容性、类加载器边界和现有日志失败隔离
契约。

