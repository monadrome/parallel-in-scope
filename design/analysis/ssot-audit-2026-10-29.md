# SSOT 一致性审计记录（2026-10-29）

> 本文按用户要求入库，基线为 `dev/v0.4.0` 的 `974c6adf`（2026-10-29）。它是本次审计的记录，不是现行行为契约；当前契约仍以 `design/` 的活文档、源码和测试为准。

## 审计口径

本次按“实现与测试说明当前行为、`design/` 活文档约束行为、ADR 只持有已明确关闭的架构决策、`design/proposal/` 仍是未落地提案”的层级交叉核对了版本、公开 API、队列准入、结果读取、开发线链接、构建与发布流程。已有未跟踪的分析稿和 `tools/` 目录属于用户工作内容，本次没有修改。

## 已直接修复的文档问题

- 开发线文档和 demo 文章的源码链接从 `main` 改为 `dev/v0.4.0`；稳定版安装说明和历史版本链接仍保留各自的稳定分支或 tag。修复覆盖双语文档首页、测试指南、完整使用指南、架构图以及 23 篇 demo 文章。
- 英文使用指南的类型化 key 入口改为当前的 v0.4 迁移指南。原链接仍指向 v0.3 中的早期草案章节，读者会被带到已被后续迁移取代的说明。
- 中文测试指南的 `valueOrThrow()` 示例补上 `ExecutionException` 声明；原代码正文承认该受检异常，但示例本身无法编译。
- 中文设计哲学和 F1 demo 不再把 `CPU_BOUND` 的拒绝入队写成自动同步执行或自动触发 `CallerRunsPolicy`。现在的说明明确：只有 `SmartBlockingQueue` 决定拒绝入队，后续行为由 executor 的 `RejectedExecutionHandler` 决定。
- F1、A1 和快速上手文章收窄了 `TaskType` 的表述：它不选择 executor 或“最优调度策略”；在普通队列上不改变行为。中文用户指南删除了已移除的 executor “标签”字段。
- 根 `AGENTS.md` 的 Mockito fallback 已与当前 POM 的 `mockito.version=5.24.0` 对齐，并改为 `$HOME` 下的可移植路径；原先的 5.23.0 和另一台机器的绝对路径已移除。

## 需要产品或实现决策的问题

### 1. ADR 0008 与当前 `TaskType` 实现互相冲突

这是本次最重要的 SSOT 冲突，不能通过改一句文案解决。

| 来源 | 当前声明 |
|---|---|
| [ADR 0008](../../adr/0008-tasktype-as-workload-fit-diagnostic.md)（Accepted） | 删除 `IO_BOUND`、`CPU_BOUND`、`MIXED`，改为 `COMPUTE`、`BLOCKING`、`UNKNOWN`；`TaskType` 只做任务/线程池负载诊断，不改变入队或拒绝行为 |
| `P0025-workload-fit-diagnostics`（已落地删除，见下方注记） | 同一套尚未落地的 API、诊断报告、观测窗口和迁移方案 |
| [TaskType.java](../../src/main/java/io/github/monadrome/parallelinscope/TaskType.java) | 仍定义 `IO_BOUND`、`CPU_BOUND`、`MIXED`；默认值仍是 `IO_BOUND` |
| [SmartBlockingQueue.java](../../src/main/java/io/github/monadrome/parallelinscope/SmartBlockingQueue.java) | `CPU_BOUND` 或 `rejectEnqueue` 会使 `offer` 返回 `false` |
| [CHANGELOG.md](../../CHANGELOG.md) 与当前用户指南 | 明确记录并说明上述旧语义仍是当前 v0.4 开发线行为 |

因此，按 ADR 读取会得到不存在的枚举常量和注册 API；按源码读取又会得到与 ADR 明确相反的入队行为。两条可行路径是：

1. 按 ADR 0008 完整落地 P0025，并同时更新活契约、双语迁移指南、README、CHANGELOG、测试和 demo；或者
2. 明确否决/取代 ADR 0008，恢复 `decision-log.md` 中的当前三值语义结论，并把 P0025 降级或删除。

在维护者选定路径前，本审计保留源码、测试和现行用户文档的旧语义，没有擅自把提案内容写成当前 API。

> 2026-10-30 注记：维护者选择路径 1。`TaskType` 已按 ADR 0008 实现，活契约为
> [task-group-api-and-options.md](../task-group-api-and-options.md) §3.2.1 与
> [workload-fit-screening.md](../workload-fit-screening.md)（ADR 0010 起改为常开筛查），提案已删除。
> 上表各行记录的是审计当时的状态。

### 2. MkDocs 编辑入口绑定 `main`

当前 [mkdocs.yml](../../mkdocs/mkdocs.yml) 的 `edit_uri` 是 `edit/main/docs/`，而开发线文档正文和源码链接跟随 `dev/v0.4.0`；GitHub Pages 又只在 `main` 推送时发布。这样在开发线本地构建页面时，“编辑此页”会打开稳定线 `main` 的文件，而把它改为开发分支又会让已发布站点的编辑入口指向开发线。

这需要发布策略决定“编辑入口跟随发布分支”还是“编辑入口跟随开发分支”，适合用部署时变量或分支专用配置解决。审计没有改动它，避免把 dev 与发布站点其中一方的入口弄错。

### 3. F1 demo 没有覆盖 `SmartBlockingQueue` 的实际行为

F1 文章现在已经说明 `TaskType` 的作用边界，但配套测试使用 `Executors.newFixedThreadPool(...)` 的普通队列，只验证滑动窗口的提交上限，并没有验证 `SmartBlockingQueue` 对 `CPU_BOUND` 的拒绝入队路径。它不是当前契约错误，却会让读者以为 demo 已覆盖文章标题所说的队列策略。若希望 demo 同时证明该行为，应增加一个明确配置 `SmartBlockingQueue` 和拒绝处理器的独立测试；这属于测试范围选择，不应把普通队列上的结果误写成 `TaskType` 的效果。

> 2026-10-30 注记：`CPU_BOUND` 拒绝入队路径已随 ADR 0008 删除；F1 改为说明滑动窗口与诊断声明，拒绝入队只由 `rejectEnqueue(true)` 表达。

## 已核对且不构成问题的项目

- 根 POM、demo POM 和 Java 8 verification consumer 都使用 `0.4.0-SNAPSHOT`；README 和首页把稳定安装示例留在 `0.3.0`，这一版本分工一致。
- `ci.yml` 的 push 触发器只包含 `main`，但 pull request 触发器覆盖所有目标分支；`snapshot.yml` 在 `dev/v0.4.0` push 上自行验证库和 demo 后才发布快照。工作流注释明确记录了这是开发线的有意策略，不是遗漏的分支名。
- 迁移指南中出现的旧 API、`BACKLOG.md` 的旧分支/提交基线，以及 ADR/设计哲学中的历史示例，均有明确的历史语境，不应替换成当前 API。
- 当前 tracked 文档中指向仓库 `main` 的链接只剩许可证文件；该文件是稳定内容，不携带开发线 API 语义。
- 多篇 demo 文章通过 `ParRuntime config` 声明并使用 `config.defaultPar()`；这些变量均有对应声明，未发现未定义示例。

## 验证记录

- `scripts/check-design-routes.py`：通过。
- `scripts/check-english-docs.py`：通过。
- `scripts/check-demo-articles.py`：通过（24 篇文章）。
- `git diff --check`：通过。
- `mkdocs build --strict --config-file mkdocs/mkdocs.yml`：通过；迁移指南中的旧结果归因锚点已统一改为当前 `#task-future-attribution` 标题锚点，构建不再报告未找到锚点。
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home mvn -B -ntp test`：通过（970 tests, 0 failures, 0 errors）。
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home mvn -B -ntp -f demo/pom.xml test`：通过（54 tests, 0 failures, 0 errors）。
- 根模块和 demo 的 `spotless:check`：均通过。
- Mockito fallback 未执行：本机 Maven 缓存没有当前 POM 所需的 `mockito-core:5.24.0` jar；常规 JDK 25 测试已通过，未触发自 attach 错误。
- `scripts/check-local-links.py`：当前扫描会进入用户已有的未跟踪 `design/analysis/flagship-review-2026-10-29/`，其中的省略号链接、仓库根相对路径和站点锚点会使检查失败；本次不修改这些用户分析稿。tracked 文档的新增和修改链接均已按仓库路径复核。
