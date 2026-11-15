# ADR 0015: blocking-queues 迁出本仓库

- Status: Accepted
- Date: 2026-11-15
- Decision scope: `io.github.monadrome:blocking-queues` 的源码归属与版本节奏
- Supersedes: [ADR 0013](0013-standalone-blocking-queues-artifact.md) 的同仓库 reactor 与版本同步部分

## Context

ADR 0013 把通用阻塞队列拆为独立 artifact，但源码仍留在本仓库的 Maven reactor 中，与 core 共用版本号。
此后队列已拥有独立仓库 [monadrome/blocking-queues](https://github.com/monadrome/blocking-queues)，
并从该仓库向 Central 发布了 `0.1.0` 正式版；该发布版的源码与本仓库模块逐字节一致。
同一套队列源码由两个仓库维护会产生两个真值源：本仓库模块的每次改动都要手工同步到独立仓库，
且 reactor 的 `0.5.0-SNAPSHOT` 版本线与独立仓库的 `0.1.x` 版本线已经分叉。

## Decision

- 删除本仓库的 `blocking-queues/` 模块；队列源码、测试、契约
  （`design/draining-queue-contract.md`）与发布流程唯一归属
  [monadrome/blocking-queues](https://github.com/monadrome/blocking-queues)。
- core 以普通外部依赖消费该 artifact：父 POM 的 `blocking-queues.version` 属性固定版本
  （当前 `0.1.0`）；升级是一次刻意的依赖变更，按依赖审计路线执行。
- ADR 0013 的 artifact 与包边界决策不变：坐标 `io.github.monadrome:blocking-queues`、
  包名 `io.github.monadrome.queues`、该 artifact 不得依赖 core、
  `SubmissionAwareBlockingQueue` 留在 core。

## Rejected alternatives

- 保留 reactor 模块直到 0.5.0 发布：两个真值源继续并存，模块改动仍需双向同步。
- core 依赖 `0.5.0-SNAPSHOT` snapshot：把 core 的构建耦合到 snapshot 仓库；`0.1.0` 正式版源码
  与模块完全一致，没有理由引入 snapshot 依赖。

## Consequences

正面：队列回到单一真值源；本仓库的构建、CI 与评审范围不再携带队列代码；两个 artifact
各自按自己的节奏发布。

代价：`mvn test` 不再运行队列测试（它们在独立仓库运行）；core 消费队列修复需要等待独立仓库
发布，然后做一次 `blocking-queues.version` 升级；本仓库文档指向队列契约的链接改为跨仓库
绝对链接。
