# Handoff：运行时执行器等待图与内部取消 token 的后续工作

> 入库说明：2026-10-27 按维护者要求入库，属交接输入，不是现行契约，不约束实现；基线 `59fe375`
> （分支 `feat/runtime-executor-wait-graph`，基于 `origin/dev/v0.4.0` 的 `3b89ee5`）。全部后续项完成后按
> `design/AGENTS.md` 第 9 条收割结论并删除本文。

## 1. 已完成（不要重做）

| 提交 | 内容 | 权威位置 |
|---|---|---|
| `8fb6a8e` | P0025 / P0026 提案、P0001 改写为 `cancelOn(ListenableFuture<?>, interrupt)` | `git show 8fb6a8e`；P0001 仍在途 |
| `73eb165` | `CancellationToken` 与 `State` 收为包私有 | [cancellation-propagation.md](../cancellation-propagation.md) §5.3、`docs/*/migration-v0.4.md`「Internal cancellation token」 |
| `59fe375` | 请求级任务图 scope 被运行时级、常开的 `ExecutorWaitGraph` 取代；公开 `ParRuntime.executorWaitReport()` / `ExecutorWaitReport` | [task-graph.md](../task-graph.md)（含被排除方案与验证基线）、`docs/*/migration-v0.4.md`「Executor wait report」、提交说明里的 MUST 清单 |

设计稿的 Kimi 评审处置记录在已删除的 P0026 末尾：`git show 8fb6a8e:design/proposal/P0026-runtime-executor-wait-graph.md`。

已跑通：`mvn -o test`（910）、demo `mvn -o test`（54，需先 `mvn -o install -DskipTests`）、`spotless:check`、
`scripts/check-design-routes.py`、`scripts/check-local-links.py`、两个新类的 PIT（75/76，残留是
`ExecutorWaitReport.empty()` 的静态初始化变异，PIT 无法触达）、`theCycleWarningRunsOffTheAdmittingThread…` 的反向验证。

## 2. 待办（按顺序）

### 2.1 独立对抗性评审 `3b89ee5..59fe375`（必须，未完成）

根 `AGENTS.md`「Adversarial Review」要求，公开 API 与并发改动在合入前做独立评审；本轮只评审了设计稿，
实现没有评审。评审者只读，用不同模型/harness。重点：

- `ExecutorWaitGraph.install` 的 CAS 循环：每个新形成的有环分量恰好宣布一次、不丢边、分量合并时重新宣布；
  与 `ParRuntime.close()` 关闭 `timeoutActionPool` 的竞态（宣布被拒绝时静默丢弃）。
- 逐条对照 `task-graph.md` 的 P1–P9 与 `Par` / `TaskGroup` 的记录点：两端都必须是本运行时注册的执行器、
  一次准入的边一起安装、已知边的热路径不写不锁不分配集合、准入线程不调 JUL、记录失败不影响准入。
- 删除 scope 的 TTL 安装 / 恢复后，TTL 传播与 bind-before-submit 顺序是否不变（`Par.submitWhileOpen`、
  `executeGlobal`、`executeCombined`、`TaskGroup.prepare`）。
- `cyclicComponents` 的可达性分量计算与 `cycleThrough`（多成员分量跳过首成员自环）的正确性；注册顺序、
  别名集合、见证与环边对齐。
- 测试缺口：CallerRuns 内联执行时边记在名义执行器上（`task-graph.md` P9 写明的近似，尚无测试）；组准备失败
  不记录（无测试）；外部运行时结构父的更多形状。

每条发现在工作树上复现后保留 / 降级 / 驳回，处置写回 `task-graph.md`（或本文）；回归测试必须反向验证；
触及的类重跑 PIT。

### 2.2 开 PR 合入 `dev/v0.4.0`

- 目标分支 `dev/v0.4.0`，不要指向 `main`。PR 描述需包含改前最佳代码、改后代码、被消除的失败模式与迁移路径
  （可直接取三个提交的提交说明）。
- CI 的 `scripts/check-api-surface.py` 会核对公开成员删除与两份 `migration-v0.4.md` 同时改动，已满足。
- 合入后删除 worktree 与本地分支：`git worktree remove .worktrees/runtime-wait-graph`。

### 2.3 实现 P0001 `cancelOn`（新能力，纯增量）

需求与协议全文见 `git show 59fe375:design/proposal/P0001-request-cancellation.md`（落地后提案按生命周期删除），要点：

- 形状：`BatchOptions.cancelOn(ListenableFuture<?> signal, boolean interrupt)`；组只在 `GroupStart` 上声明一次。
  `TaskOptions` 是否也需要，实现前先按 `first-principles.md` 判断。
- 来源以任何方式完成即取消执行 token，first-wins；中断意图由参数决定，不从来源的完成方式推断；
  `interrupt=false` 先赢时，后到的 deadline / fail-fast MUST NOT 升级为中断。归因复用 `GROUP_CANCELLED`。
- 注册：声明期只保存；准入期、bind 与提交之前注册；先装可切断持有者再 `addListener`（来源已完成会同步回调，
  此时 MUST 在任何 body 进入用户代码前取消）；持有者不引用执行 token / future / 结果；token 任一终态或
  准备失败即切断；空批次 / 空组不注册。
- 库 MUST NOT 完成或取消用户的 future。
- 必测：预先完成（成功 / 失败 / 取消）的来源、正常完成后解绑与引用释放、与祖先取消 / deadline 竞速、
  `interrupt=false` 不被升级、空执行、准备失败、内联执行、同一来源跨多次执行复用。
- 落地时 P0001 蒸馏进 `synchronous-scope-exit.md`（第四个取消来源）与 `cancellation-propagation.md`，删除
  提案与路由行；更新 user-guide、两份 `migration-v0.4.md`、CHANGELOG；做独立评审与 PIT。

### 2.4 可选

- 热路径成本只做了推算：可以测一次嵌套 `map` 在已知边、首次新边、并发首次新边下的分配与延迟。
- 有环分量的见证目前只有首次观测的单元名；若需要首次 / 最近观测时间，注意时钟域（不要用 deadline 时钟）。

## 3. 环境与坑

- 构建必须用 JDK 25：`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home`，
  Maven 用 `-o`。
- demo 依赖本地安装的 `0.4.0-SNAPSHOT`：先在根目录 `mvn -o install -DskipTests`，再 `cd demo && mvn -o test`。
- 主 checkout 可能有其他会话的未提交改动；在 worktree 里工作，不要动主 checkout。
- 非交互评审：`codex exec -s read-only -C <dir> "<prompt>" < /dev/null`（不重定向 stdin 会卡在读 stdin）；
  `kimi -p` 不能与 `--plan` 同用，Kimi 服务端可能 429，可用 `kimi -c -p` 续跑。长任务用 `nohup … &` 脱离工具超时。
- 提交身份：GitHub 仓库用 `EricLin <linqinghua4@gmail.com>`。

## 4. 建议加载的 skill

- `light-explore` 或 `brainstorming`：开始 2.3 前对齐 `TaskOptions` 是否需要 `cancelOn`、空执行的语义。
- `multi-review`：执行 2.1 的并行多模型评审。
- `git-commit-helper`：按团队规范写提交说明。
