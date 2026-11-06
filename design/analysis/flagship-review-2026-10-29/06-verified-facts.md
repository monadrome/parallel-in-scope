# 已核实事实与数字口径

> 快照：2026-10-29，`dev/v0.4.0` @ `86450971`。这是分析稿，不是契约。
> 目的：避免三份评审和后续对话继续引用不同版本的测试数、PIT 结果或对外声明。

## 仓库与发布

- 仓库：`monadrome/parallel-in-scope`，GitHub。
- 开发线：`dev/v0.4.0`，版本 `0.4.0-SNAPSHOT`。
- 最新稳定版：`0.3.0`，已发布到 Maven Central。
- 当前 About：中文，含“死锁检测”；与实现里的 “not a deadlock proof” 冲突。
- Homepage：`https://monadrome.github.io/parallel-in-scope/`，实测 HTTP 200。
- Javadoc：
  - `https://monadrome.github.io/parallel-in-scope/apidocs/`：404。
  - `https://javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0`：200。

## 测试与规模

- `mvn clean test`（JDK 25.0.4.1）：**970 tests / 85 classes / 0 failures / 0 errors / 0 skipped / 约 84 秒**。
- 测试代码约 27.2k 行，主代码约 13.8k 行，比例约 **2.0:1**。
- 85 个测试类中，**59 个直接启动真实线程池**，仅 4 个使用 Mockito。
- 旧口径 **1062 tests / 106 classes 已过期**：来自 2026-10-27 的 `target/surefire-reports`，其中包含已删除的 `TaskGraph*Test`。对外统一用 970 / 85；面试中不要用无法当场复现的数字。

## PIT / 变异测试

- scoped PIT：`SlidingWindowSubmitter`，**26 mutants / 21 killed / 3 timed out / 2 survived**。
- 运行期间出现 **3 次 minion `TIMED_OUT`**。按仓库自身规则记为 **blocked，不是 pass**。
- 因此：**不能**把这次 PIT 说成“变异测试通过”，也不能作为面试证据。可以把它作为“已识别的测试缺口线索”，但必须同时说明运行被 timeout 阻断。
- 仓库里已有历史 PIT 结果（例如 `design/runner-interrupt-delivery.md`、`design/confirmed-proposal-defects.md`），但它们是旧快照，引用前需核对当前代码。

## CI 与发布流程

- `.github/workflows/ci.yml`：`push` 只在 `main`，`pull_request` 触发；**不覆盖 `dev/v0.4.0` 直推**。
- `.github/workflows/codeql.yml`：同样只覆盖 `main`。
- 评审时 `dev/v0.4.0` 领先 `main` 约 52 个 commit；这些提交没有跑过 CI，而 README 首行展示 CI badge。
- 两个 Release run 失败：
  - `v0.3.0-SNAPSHOT`：run `40814798148`，`central-publishing-maven-plugin` 对 snapshot endpoint 返回 **HTTP 401**。
  - `v0.2.0-SNAPSHOT`：run `40814795536`，同样 **HTTP 401**。

## 文档与仓库卫生

- `README.md` 首屏说最新稳定版 `0.3.0`，但后面仍有 “Staying on the stable `0.2.0` line?” 引导；缺少 0.3.0 的独立入口。
- `README.zh-CN.md` 文档表缺 v0.4 迁移行。
- 根目录 `todo.md` 被 tracked（约 191 行），引用 `/tmp` 路径；内容已被 `BACKLOG.md` / `design/decision-log.md` 覆盖。
- `BACKLOG.md` 总览仍把 P-P7“每批次一个阻塞 submitter 线程”列为开放，但同文件后文和代码表明 **2026-10-04 已修复**（完成事件驱动补窗，反向验证 24 条等待线程 → 0）。这是“文档说有问题、代码说没有”的典型腐烂点。
- `docs/assets/ownership-layers.svg` 存在（900×610 三层所有权图），但没有任何 Markdown 引用它。
- `docs/en/interactive-demo.html` 是 `lang="zh-CN"`；`docs/en/index.md` 自述 demo 文章是中文制品。

## GitHub 元数据（评审时快照）

- 1 star、0 fork、2 open issues、1 open PR。
- 无 profile README。
- Pinned repos 中 `parallel-in-scope` 只有 1 star，但同账号还有 TTL（8.3k）等项目；置顶顺序没有突出旗舰项目。

以上数字会随时间变化；面试前重新跑一遍 `mvn clean test` 和 `gh repo view`，不要背过期数字。
