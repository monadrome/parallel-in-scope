# parallel-in-scope

:material-language-java: 面向 **Java 8+** 的结构化并发工具包

> **⚠️ 项目状态：开发中（Pre-release）**
> 本项目仍在积极开发中，API 可能会发生变化。欢迎通过 Issue 提交反馈和建议。

parallel-in-scope 聚焦解决 Java 8 并行编程中的典型痛点：**取消信号难传播、`ThreadLocal` 上下文丢失、嵌套并行死锁难诊断**。相比 CompletableFuture 链式编排与 `ExecutorService + invokeAll` 传统模型，更强调**结构化语义与工程可控性**。

目标很直接：在不升级 JDK 的前提下，让并发代码从"能跑"走向"**失败即止、取消级联、死锁可见**"。

---

## 核心特性

- :zap: **快速失败（Fail-Fast）** —— 任一子任务抛异常，立即取消同批所有剩余任务
- :shield: **协作式取消（Cooperative Cancellation）** —— 父子令牌自动级联，Late-Binding 避免竞态
- :link: **上下文传播（Context Propagation）** —— 基于 Alibaba TTL 的两级 Map 接力，零侵入业务代码
- :rocket: **滑动窗口调度（Sliding-Window Scheduling）** —— "完成一个补一个"，线程池满载又不溢出
- :plugs: **可插拔扩展（Pluggable SPI）** —— `TaskListener` / `ExecutorResolver` / `LivelockListener`
- :mag: **死锁检测（Deadlock Detection）** —— 请求级 DAG 图自动记录依赖、请求结束时环路检测
- :target: **任务类型感知调度** —— CPU 密集型 `offer()` 返回 `false` 触发 `CallerRunsPolicy`，IO 密集型正常排队

---

## 三分钟示例

```java
ParConfig config = ParConfig.builder()
    .executor("io-pool", Executors.newFixedThreadPool(10))
    .build();
Par par = new Par(config);

ParOptions options = ParOptions.ioTask("fetchData")
    .parallelism(5)
    .timeout(3000)
    .build();

List<String> urls = Arrays.asList("url1", "url2", "url3", "url4", "url5");
AsyncBatchResult<String> result = par.map(
    "io-pool",
    urls,
    url -> httpClient.fetch(url),
    options
);

List<ListenableFuture<String>> futures = result.getResults();
```

`Par.map` 内部自动处理滑动窗口调度、超时控制、快速失败取消和上下文传播，无需额外配置。

👉 完整步骤见 [快速上手](getting-started.md)。

---

## 文档导航

| 主题 | 说明 |
|---|---|
| [快速上手](getting-started.md) | Maven 依赖、初始化、`Par.map` 用法 |
| [协作式取消](guide/cooperative-cancellation.md) | 取消令牌、checkpoint、超时如何交互 |
| [空指针注解策略](guide/nullability-annotations.md) | 混合 JSR-305 / Checker Framework 策略 |

---

## 项目链接

- [README (中文)](https://github.com/HuaTalk/parallel-in-scope/blob/main/README.md)
- [README (English)](https://github.com/HuaTalk/parallel-in-scope/blob/main/README_EN.md)
- [Idea Graveyard](https://github.com/HuaTalk/parallel-in-scope/blob/main/IdeaGraveyard.md)
- [Issues](https://github.com/HuaTalk/parallel-in-scope/issues)

!!! info "关于本站"
    本站基于 [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) 构建，源码位于仓库 `mkdocs/` 目录，通过 GitHub Actions 自动部署到 GitHub Pages。
