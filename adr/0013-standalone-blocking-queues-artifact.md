# ADR 0013: 通用阻塞队列独立发布

- Status: Accepted
- Date: 2026-11-07
- Decision scope: `DrainingBlockingQueue` 与 `ResizableBlockingQueue` 的产物和包边界
- Supersedes: [ADR 0006](0006-queues-ship-with-core.md)

## Context

`DrainingBlockingQueue` 与 `ResizableBlockingQueue` 不依赖结构化并发内核、取消、任务上下文或执行器所有权。
它们实现的是可安装到任意 `BlockingQueue` 消费者上的通用队列能力，尤其是动态容量调整和排干式关闭。
把它们绑定在 `parallel-in-scope` artifact 中，会让只需要队列的应用引入无关的 core 入口和依赖。

`SubmissionAwareBlockingQueue` 不属于本次拆分：它读取 `SubmissionScope` 和 `MultiTaskContext`，其入队拒绝语义是 core 提交协议的一部分。

## Decision

发布独立 artifact：

```text
io.github.monadrome:blocking-queues
```

公开 Java 包迁移为：

```text
io.github.monadrome.queues
```

该 artifact 包含：

- `DrainingBlockingQueue`；
- `ResizableBlockingQueue`。

`parallel-in-scope` 以 compile 依赖使用 `blocking-queues`，并继续在根包提供 `SubmissionAwareBlockingQueue`。
独立队列 artifact 不得依赖 `parallel-in-scope`。

两个队列与 core 使用同一版本号，直到有证据表明它们需要不同的发布节奏。

## Consequences

正面：只需要通用队列的用户可以依赖更小、更明确的 artifact；队列可以独立维护契约、测试和文档；
`ResizableBlockingQueue` 不再被当前项目名称限制。

代价：仓库改为 Maven reactor；需要维护两个构件的 source、Javadoc、签名和发布元数据；
core 与 queue 的版本在初期仍保持同步。

`SubmissionAwareBlockingQueue` 仍然依赖 `ResizableBlockingQueue`，因此 core 仍会传递依赖 `blocking-queues`。
本次拆分解决的是独立使用入口和产品定位，不承诺完全解耦 core 的构建或发布节奏。

## Rejected alternatives

- 保留 `parallel-in-scope-queues`：它仍把通用队列绑定在结构化并发项目名称下，不符合队列作为独立产品的定位。
- 只改 artifact 不改 Java 包：会让独立构件继续暴露 `parallelinscope` 命名空间，增加长期品牌和迁移歧义。
- 把 `SubmissionAwareBlockingQueue` 一并移出：会把 core 的提交上下文协议变成通用队列公共 SPI，扩大 API 和并发语义边界。
