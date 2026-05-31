# ADR 0006: queue 包与 core 同产物发布

- Status: Accepted
- Date: 2026-09-14
- Decision scope: `queue` 包的产物边界与评审责任归属
- Supersedes: None

## Context

`queue` 包包含两个公开类，合计 2 180 行，约占 `src/main/java` 总量（10 233 行）的 21%：
`DrainingBlockingQueue`（1 642 行）与 `VariableLinkedBlockingQueue`（522 行）。两者都带
专属行为契约与测试（分别 1 448 行与 1 289 行）。

包内引用关系是刻意稀疏的：`DrainingBlockingQueue` 在 `src/main` 中零引用；`VariableLinkedBlockingQueue`
的唯一内部消费者是 root 包的 `SmartBlockingQueue`（委托实现）。`SmartBlockingQueue` 依赖
`TaskType`、提交作用域与任务上下文等 core 类型，因此必须留在 core；库自身从不构造它——
它由用户自行实例化并安装到自己的 `ThreadPoolExecutor` 上。

项目定位（`AGENTS.md`）已把 `queue` 声明为 "independent general-purpose queue
implementations"，即与 core 无代码耦合是有意的，不是欠账。库处于 0.x 预稳定窗口，0.3 将
冻结公开面：冻结后删除公开类不再可行，拆分的迁移成本随存量用户增长而上升。因此"不决定"
等于默认维持现状，产物边界必须显式拍板。

## Decision

`queue` 包与 core 同产物发布，不拆分、不私有化；既有定位不变。

可执行规则：

1. 不引入多模块构建：`pom.xml` 保持单产物，不为队列引入 parent/module 结构或独立
   artifactId。
2. `SmartBlockingQueue` 留在 root 包；`rejectEnqueue` 与 `TaskType` 的排队/拒绝语义留在
   core，不随队列包迁移。
3. `queue` 的公开契约与 [draining close contract](../design/draining-queue-contract.md)
   一并作为本库的公开产品维护，队列缺陷由本仓库承担归因与修复。
4. core 代码不得新增对 `queue` 包类型的依赖；`SmartBlockingQueue` →
   `VariableLinkedBlockingQueue` 这一处委托依赖保持不变。
5. 产物边界不再作为开放问题重新提出：设计评审、缺陷分诊与重构提案都不再把"拆分/删除
   queue 包"列为候选方案。

## Alternatives Considered

### 选项 A：拆为兄弟产物 `…-queues`（core 依赖它）

收益仅限 core 评审面缩减约 21% 与依赖可选性两项。同 reactor 构建下两产物发版节奏实际仍同步，
"发布节奏解耦"不成立。代价是为一个 Java 8 基线库引入永久的多模块构建与发布基础设施
（parent POM、模块版本策略、发布配置、站点与文档归属拆分）。收益不足以承担该成本，否决。

### 选项 B：从公开面删除（包私有化）

`DrainingBlockingQueue` 零内部引用恰恰说明它的用户全在库外，删除等于砍掉一个已有用户的
公开产品；且与"独立通用队列实现"的既定定位直接冲突，并作废 1 448 行契约测试。除非先修订
定位，否则该选项不在桌上，否决。

### 选项 C：维持现状但不固化结论（悬置）

零代码成本，但把"同产物发布是否有意"留给每个后续读者重新推导，决策会被反复提出。悬置不是
终态，否决。

## Consequences

正面：零构建与发布成本；队列保持独立契约与专属测试；存量用户无需迁移；`queue` 与 core 之间
不存在需要长期协调的版本关系。

代价：core 永久多承担约 21% 的评审面，且队列缺陷直接归因于本库。这是**有意承担**的结论：
该评审面有独立契约、充分测试，与 core 无耦合，评审负担是"多读一个包"而非"多一处纠缠"。

风险与边界：单产物意味着队列的破坏性变更与 core 共享同一发布窗口和迁移文档，
`DrainingBlockingQueue` 的公开契约变更必须比照 core API 走 0.x 破坏性变更流程。

## Reconsideration

仅在下列任一情形出现时重开本决策，并以新 ADR 取代本记录：

- 队列缺陷在缺陷预算中的占比持续高于 core 代码；
- 出现"只需队列、不要 core"的真实用户需求；
- 需要为队列引入与 core 不同的依赖或 Java 版本基线。
