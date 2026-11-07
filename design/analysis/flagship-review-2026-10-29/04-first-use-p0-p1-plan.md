# parallel-in-scope 首次使用者体验优化方案

- 对象仓库：`/Users/linqh/IdeaProjects/vformation`，分支 `dev/v0.4.0`，基线 `86450971`
- 本轮性质：只出方案，未修改仓库任何文件（文末附 `git status` 与验证记录）
- 输入：交接稿的 P0/P1 复现结论 + 本轮对当前工作树的逐条复核（复核结果与交接稿有出入的地方在 §8 单列）

---

## 1. 结论摘要

| # | 先做的事 | 解决什么 | 预期收益 |
|---|---|---|---|
| 1 | README 首屏重写为**一条可复制即跑的最小示例**（单依赖、单池、单 `map`、含失败分支、含预期输出） | 首屏依赖 `User/Account/Profile/userService` 等未定义上下文，复制不能跑 | 陌生用户 10 分钟内拿到 `SUCCESS:[1, 4, 9, 16]`；本轮已验证 0.3.0 与 0.4 两版代码在 JDK 8 下编译并运行 |
| 2 | **stable / next 通道分离**：README 稳定段只链"已发布站点"或 tag；仓库内 next 文档每页带版本标记，并加机械校验 | README 装的是 `0.3.0`，却把 "Full user guide" 链到 0.4 指南（`README.md:128`），0.3 用户照抄会撞 `TaskKey`、非受检 `valuesOrThrow()` | 消灭"坐标与文档不同代"这一类首次失败；后续每次发版不再靠人记 |
| 3 | 修 **18 篇文章缺 `.defaultPar(...)`** + 4 篇描述**已删除机制**（`SubmitterPool`、`TaskGraph`）的正文 | 文章代码块复制即抛 `IllegalStateException: ParRuntime has no default Par`（本轮实测）；E2/I3/C1/C2 讲的是 0.4 里已不存在的内部类 | demo 从"读着对、抄了错"变成可执行事实；同时给文章加静态门禁防复发 |
| 4 | **阻塞语义 + 受检异常 + 失败分支**三件套进首屏（各 1–3 行） | `map` 同步阻塞、`Function` 不能抛 `IOException`、只打印 `outcome()` 会把 `USER_FAILURE` 当成功 | 减少"为什么卡住/为什么编译不过/为什么失败没报错"三类首问 |
| 5 | **构建前置上移**（JDK 25）+ 文档入口补齐（Gradle / Javadoc / CONTRIBUTING / CHANGELOG）+ 依赖版本说明 | JDK 21 直接撞 enforcer；README 文档表缺 4 类入口；传递依赖版本无处可查 | 贡献者少一次"环境不明"的往返；选型信息齐全 |

这五件事合起来把"首次成功"从"要读完整份 guide 才能拼出来"降到"照 README 首屏 + 一次失败改写即可"。全部是文档与示例层改动，**不触碰库 API**。

---

## 2. 用户旅程与成功标准

### 2.1 两类首次使用者

- **A. 只想用库的应用开发者**（不关心仓库构建）：从 README / 搜索引擎进来，目标是"加依赖 → 跑通一次 → 知道失败长什么样"。
- **B. 想改库或读源码的贡献者**：从 CONTRIBUTING 进来，目标是"clone → `mvn test` 绿 → 知道分支与验证责任"。

### 2.2 "首次成功"的可验证标准

以陌生使用者、一个干净 shell、网络可用为前提：

| 步骤 | 判据 | 时间预算 |
|---|---|---|
| S1 | README 首屏 30 秒内能读到：运行时要求（Java 8+）、依赖坐标、构建 JDK 要求（仅贡献者） | 0.5 min |
| S2 | 新建独立 Maven 项目，只加一条依赖（无需快照仓库），`mvn -q package` 通过 | 5 min |
| S3 | 粘贴首屏示例，`java` 运行输出 `SUCCESS:[1, 4, 9, 16]` | 2 min |
| S4 | 把 `n * n` 改成 `throw new IllegalArgumentException("bad " + n)`，看到**失败分支**输出而不是静默 | 1 min |
| S5 | 能说出三条：调用会阻塞、超时必须显式声明、异常要在 lambda 内处理 | 1 min |
| 合计 | | **≤ 10 min** |

附加的"第二次成功"：使用者明确知道"我要 0.4 的话去哪看"——即 README 有显式的 development-line 入口，且该入口的文档自报版本。

### 2.3 可机械化的验收

- S2/S3/S4 由一个**小而固定的验证工程**承担（见 §5.4），CI 每次都跑，而不是靠人肉复核 README。
- S5 由首屏结构保证（三条契约 + 两段说明在场即可，不需要自动化）。
- "第二次成功"由 `scripts/check-doc-versions.py` 保证（见 §5.1）。

---

## 3. 按工作流拆分的方案

### 3.1 README 首屏（P0-1）

**目标**：从"能力展示型 README"改成"最小可运行 + 三条契约 + 去哪里深入"。

**现状问题**（`README.md`）：
- `:17` Quick Start 同时放 batch、group、两个 executor、`awaitQuiescence`、TTL，且依赖 `User`、`Account`、`Profile`、`userIds`、`userService` 等未定义符号。
- `:47` 在首例里写 `.taskType(TaskType.IO_BOUND)`（默认值、且普通池上无效果，见 §3.8）。
- 无预期输出、无失败分支、无"会阻塞"提示。
- 中英两份 README 结构相同，需同步改（`README.zh-CN.md:17` 起）。

**建议首屏结构**（英文；中文同构）：

````markdown
# parallel-in-scope

<badges>

Run a finite list in parallel on your own thread pools: a forced deadline, fail-fast
cancellation, bounded sliding-window submission, and results you can read per element.
Java 8+, single dependency, no framework.

**Stable `0.3.0`** · [Quick start](#quick-start) · [User guide](https://monadrome.github.io/parallel-in-scope/user-guide/) ·
[Javadoc](https://javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0) ·
[Examples](demo/README.en.md) · [Development line `0.4.0-SNAPSHOT`](#development-line)

## Quick start

Runtime: **Java 8 or later**. Nothing else to install — Guava and TransmittableThreadLocal
arrive as compile-scope transitive dependencies.

Maven:

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.3.0</version>
</dependency>
```

Gradle (Groovy): `implementation 'io.github.monadrome:parallel-in-scope:0.3.0'`
Gradle (Kotlin): `implementation("io.github.monadrome:parallel-in-scope:0.3.0")`

```java
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
        } catch (ExecutionException failure) {
            System.out.println("FAILED:" + failure.getCause());
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
```

Output:

```text
SUCCESS:[1, 4, 9, 16]
```

Three things that shape every call:

- **`map` blocks.** It returns only after every element settles (including the bounded
  cleanup wait). Interrupting the calling thread does not cancel the batch.
- **The deadline is a forced choice.** `BatchOptions.timeout(name, duration)` or
  `BatchOptions.inheritTimeout(name)` — there is no third state.
- **A batch is fail-fast.** The first failure cancels the rest, including elements never
  submitted; read `outcome()` per element. `valuesOrThrow()` rethrows the first recorded
  failure — on `0.3.x` as a checked `ExecutionException`, on `0.4.0-SNAPSHOT` unchecked
  ([what changed](docs/en/migration-v0.4.md#batch-aggregate-reads)).

A body that can throw a checked exception must wrap it — `Par.map` takes
`java.util.function.Function`:

```java
TaskBatchResult<Price> prices = io.map(skus, sku -> {
    try {
        return client.fetch(sku);
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);   // surfaces through valuesOrThrow()
    }
}, BatchOptions.timeout("prices", Duration.ofSeconds(3)));
```

## Development line (`0.4.0-SNAPSHOT`)

Typed `TaskKey` group declarations, unchecked aggregate reads, and the always-on executor
wait graph. These docs track the development line and are **not** the `0.3.0` contract:
[English docs](docs/en/index.md) · [中文文档](docs/zh/index.md) ·
[v0.4 migration guide](docs/en/migration-v0.4.md).

## What else the library does

- Typed heterogeneous task groups with a terminal combine → [guide](…/user-guide/#task-group)
- TTL context propagation, MDC / OpenTelemetry recipes → [integration recipes](…)
- Executor-wait and starvation diagnostics → [guide](…/user-guide/#observe-direct-executor-work)
- Graceful shutdown ordering → [guide](…/user-guide/#application-shutdown)
- Stable-batch behaviour and `TaskType` admission (only meaningful with `SmartBlockingQueue`)

## Documentation
<table as today, plus the rows from §3.9>

## Requirements and build
<table from §3.5>

## License
````

**原有内容搬到哪**：

| 原 README 内容 | 去处 |
|---|---|
| 任务组 / `runAll` / `terminalValueOrThrow` 示例 | `docs/{en,zh}/user-guide.md#task-group`（已存在，链接即可） |
| `ParRuntime.close()` / `awaitQuiescence` 停机顺序 | user-guide `#application-shutdown`（已存在） |
| TTL 段落 | user-guide `#integration-recipes`（已存在 MDC/OTel 配方） |
| `TaskType` | user-guide `#batch` 中已有的 `SmartBlockingQueue` 段（`docs/en/user-guide.md:91-117`） |
| "三条契约" | 保留在首屏（就是这三条，不再扩写） |
| Core Capabilities 列表 | 精简为 "What else the library does"（5 条，全部带链接） |

**验收**：
1. 新首屏示例在 JDK 8 下编译并运行，输出与文档一致（本轮已对 0.3.0 jar 与 0.4 `target/classes` 各验证一次，见 §8.1）。
2. 首屏代码块不含 `User`/`Account`/`Profile`/`userService`/`userIds` 等未定义符号。
3. 首屏不含 `group(`、`TransmittableThreadLocal`、`awaitQuiescence`、`TaskType`。
4. `python3 scripts/check-local-links.py` 通过；`mkdocs build --strict` 通过（中英两份 index 同步）。
5. Phase 2 起由 `verification/stable-quickstart`（§5.4）与 snippet 注入（§5.3）持续保证。

**工作量**：S（英文 0.5 天 + 中文 0.5 天）。
**风险**：首屏一旦被后续 PR 改坏又会漂移 → 由 §5.4 的"可编译锚点"兜底；中文与英文首屏若只改一边，会回到 P0-4 的不对称。

---

### 3.2 版本化文档（P0-2）

**先纠正一个前提**（本轮核实，交接稿未覆盖）：

- `gh-pages.yml` 只在 **push 到 `main`** 且 `docs/**` 或 `mkdocs/**` 变化时构建。
- 本地 `origin/main`（`0751fd19`，2026-10-28）的 `pom.xml` 版本是 `0.3.0`，其 `docs/en/user-guide.md` 开头写的是"documents the `0.3.0` release"。
- 因此**线上站点当前发布的是稳定线文档**，本身是自洽的；`README.md:122` 的 "Online documentation" 指向它没有问题。
- 真正的错配发生在**仓库内**：`dev/v0.4.0` 的 README 让用户装 `0.3.0`，却在 `:124`、`:128`、`:130` 链到本分支的 0.4 文档与 0.4 中文入口；`docs/en/index.md:9` 把 0.4 guide 标为 "Primary guide"。
- 另一层风险在**未来**：0.4 合入 `main` 后，站点整体切换为 0.4 文档，0.3 文档只能靠 git tag 找。

**推荐方案：三通道，不做目录复制、不引入版本选择器**

| 通道 | 载体 | 谁看 | 依据 |
|---|---|---|---|
| 当前稳定线 | 线上站点（由 `main` 构建，保持现状） | 只想用稳定版的用户 | 现状已正确，只需 README 指向它 |
| 历史稳定线 | GitHub tag 链接（`tree/v0.2.0/docs/en/user-guide.md` 形式） | 老版本用户 | **仓库已有先例**：`README.md:105-106` |
| 开发线 | 仓库内 `docs/` + 每页版本标记 + README 的 `## Development line` 区块 | 跟进 0.4 的用户/贡献者 | 本分支文档本来就在写 next |

**具体改动**：

1. `README.md` / `README.zh-CN.md`
   - 文档表"Full user guide"行改为线上稳定指南：`https://monadrome.github.io/parallel-in-scope/user-guide/`，文案注明 `Stable 0.3.0`。
   - 新增一行 `Development line (0.4.0-SNAPSHOT)` → `docs/en/index.md`（中文 → `docs/zh/index.md`），并注明"API 与 0.3.0 不同"。
   - `English documentation` / `中文文档` 两行从"用户指南入口"降级为"开发线文档入口"，或直接并入 Development line 行。
2. `docs/en/index.md`、`docs/zh/index.md` 顶部加版本横幅（现有 user-guide 第 3 行已有同类句子，提到 index 层）：
   ```markdown
   > These pages document the `0.4.0-SNAPSHOT` development line. Installing `0.3.0`?
   > Read the published [0.3.0 user guide](https://monadrome.github.io/parallel-in-scope/user-guide/).
   ```
   中文同义。同时把 `Primary guide` 的措辞限定为"本开发线的主指南"。
3. 每个 `docs/en/**/*.md`、`docs/zh/**/*.md` 首行加不可见标记：
   ```markdown
   <!-- docs-version: 0.4.0-SNAPSHOT -->
   ```
   迁移指南按"目标版本"标注（`migration-v0.3.md` → `0.3.0`），由白名单显式列出。
4. 新脚本 `scripts/check-doc-versions.py`（见 §5.1）在 CI `docs` job 中运行。

**为什么不选目录复制**（`docs/en/0.3/` + `docs/en/next/`）：
- 当前 `docs/{en,zh}` 各 12 个页面（`find docs/en -name '*.md' | wc -l` = 12）；每支持一个并发版本就要复制 24 个页面并维护其中的相对链接，`mkdocs-static-i18n`（`docs_structure: folder`）与手写 nav 都要按版本再分叉，`mkdocs/mkdocs.yml:12-30` 的 nav 也要复制一份。
- 收益只是"站点内能点回旧版本"，而旧版本最常见的诉求是"给我 0.2 的 guide 长什么样"——tag 链接已经满足（`README.md:105` 已在用）。
- 结论：目录复制是 L 成本、低收益；等出现"同时支持两个 minor 且都要在线可达"的真实需求再上版本选择器。

**备选方案（若维护者坚持站点内可切版本）**：`mike`
- 做法：`mike deploy --push --update-aliases 0.3.0 stable`（发版）/ `mike deploy --push --update-aliases 0.4.0 next`（`dev/v0.4.0` 推送）；`mkdocs.yml` 配 `extra.version.provider: mike`。
- 代价：Pages 部署模型要从"artifact 部署"切到 `gh-pages` 分支；`/zh/user-guide/` 这类现有深链会 404，需要 `mkdocs-redirects` 逐条兜（`mkdocs/mkdocs.yml:108-121` 已有 9 条历史重定向，继续加会膨胀）。
- 结论：不作为 Phase 1/2 目标，列入 §7 待确认。

**验收**：
- `grep -n "user-guide.md" README.md README.zh-CN.md` 的稳定段不出现仓库内 next 页面。
- `python3 scripts/check-doc-versions.py` 通过；故意把 `README.md` 的稳定段链到 `docs/en/user-guide.md` 时脚本失败并给出替代文案。
- `mkdocs build --strict` 通过。

**工作量**：S–M（内容 0.5–1 天；脚本 + 测试 0.5 天）。
**风险**：`main` 与 `dev/*` 的文档代际天然不同，任何"跨分支相对链接"都会漂；因此规则是"稳定段只允许 absolute site URL 或 tag URL"，这一点必须由脚本而不是评审保证。

---

### 3.3 demo 文章与测试同步（P0-3）

**本轮核实的事实**（替换交接稿数字，按当前工作树统计）：

- `demo/docs/zh-CN/articles/` 共 **24** 篇；提到 `.defaultPar()` 的 **20** 篇；其中 builder 里真的写了 `.defaultPar(ParId.of(...))` 的只有 **2** 篇（`G1`、`QUICK-START`）→ **18 篇缺注册**。
- 缺注册并非"文档省略"：`config.defaultPar()` 在 builder 未设默认时抛 `IllegalStateException: ParRuntime has no default Par`（`src/main/java/.../ParRuntime.java:251-252`，本轮实测）。
- 对应测试**无一例外都能跑**：21 个 article 测试中 20 个写了 `.defaultPar(...)`（`defaultPar` 共出现 40 次），唯一没写默认的 `C2_CachedVsFixedPoolTest` 直接用 `config.par(...)` 取 `Par`——即"测试可跑、文章不可抄"。
- 另有 4 篇正文描述的是 **0.4 已删除的内部机制**（不是拼写问题）：
  - `C1-thread-pool-deadlock.md:29`、`C2-cached-vs-fixed-pool.md:42,50`：`TaskGraph`、`Graphs.hasCycle()`、`canDeadlock()`。当前源码里**没有** `TaskGraph` 类；公开诊断是 `ParRuntime.executorWaitReport()`（`ParRuntime.java:289`）+ `openThreadPoolStarvationObservation(...)`。
  - `E2-submitter-pool-offloading.md:37-43,58`、`I3-sliding-window-vs-semaphore.md:89`：`SubmitterPool` / `submitterPool` / `Par-Submitter-*`。0.4 已删（`BACKLOG.md` P-P7；`design/sliding-window-refill.md` 明确 "MUST NOT 为每个批次驻留一条等待提交线程"）。
- 23 处 `blob/main/...` 测试链接指向 `demo/src/test/java/demo/article/*`，当前分支全部存在；但对**新增**测试，`main` 上还没有该文件 → 文章发表后到合入前会 404，且 `check-local-links.py` 检查不到绝对 URL。

**必须立即修的文章清单与修法**（原则：以 `demo/src/test/java/demo/article/<同名>Test.java` 的可执行代码为准回填）

| # | 文件 | 问题 | 修法 |
|---|---|---|---|
| 1 | A1, A2, A3, B1, B2, C1, C2, D1, E1, E2, F1, G2, G3, G4, G5, G6, H1, I4（18 篇） | builder 缺 `.defaultPar(...)`，`config.defaultPar()` 抛异常 | 在 `ParRuntime.builder()` 链上补 `.defaultPar(ParId.of("<id>"))`，id 与测试一致；或改用 `config.par(ParId.of(...))` 显式取 |
| 2 | `G4-named-executor-pool.md:46-67` | 注册了 `io-pool`/`cpu-pool`/`report-pool`，却只用同一个 `par` 跑三类任务，与结论矛盾；`report-pool` 在测试里没有对应调用 | 改为 `Par ioPar = config.par(ParId.of("io-pool"))`、`Par cpuPar = config.par(ParId.of("cpu-pool"))` 分别 `map`；`report-pool` 要么在测试里补一条真实调用，要么从文章删除（推荐删除，避免无覆盖的示例） |
| 3 | `C1-thread-pool-deadlock.md:29,31,53-63` | 正文讲已删除的 `TaskGraph`/`hasCycle()`/`canDeadlock()`；代码内层仍用外层 `par` | 正文换成 `executorWaitReport()` / 饥饿观测窗口；代码改为 `Par innerPar = config.par(ParId.of("inner-pool"))` 并在内层 `innerPar.map(...)`（与 `C1_ThreadPoolDeadlockTest.java:73-93` 一致） |
| 4 | `C2-cached-vs-fixed-pool.md:42,50` | 同上，`canDeadlock()` 不存在 | 改为"`ThreadPoolExecutor` 队列可读性决定能否观测 + `SmartBlockingQueue` 决定能否拒绝入队"，并链 user-guide `#observe-direct-executor-work` |
| 5 | `F1-cpu-task-queuing.md:36,40,53,67-71` | 声称 `CPU_BOUND` "触发 `CallerRunsPolicy`"、"零队列延迟"；示例用的是普通 `Executors.newFixedThreadPool`，实际该选项无效并告警 | 示例改为 `new ThreadPoolExecutor(4, 4, 0L, MS, new SmartBlockingQueue<>(64), new ThreadPoolExecutor.CallerRunsPolicy())`；正文改成"`SmartBlockingQueue` 拒绝入队 → 由池自己的拒绝策略决定（`CallerRunsPolicy` 则就地执行）"；删掉"零队列延迟"这类绝对表述。测试 `F1_CpuTaskQueuingTest` 同步换池并断言队列深度/拒绝次数，而不是只断言 `options.taskType()` |
| 6 | `E2-submitter-pool-offloading.md:35-43,58` | 描述已删除的 `SubmitterPool` | 改为事件驱动补窗：完成线程释放槽位并认领下一个（链 `design/sliding-window-refill.md`）；保留"1 线程池不死锁"的结论与测试 |
| 7 | `I3-sliding-window-vs-semaphore.md:89` | "使用独立的 `submitterPool`" | 删掉该句，改为"补窗发生在让元素到达终态的那条线程上，不额外驻留提交线程" |
| 8 | `A1-cancel-true-ignored.md:31-33` | "`taskType` … 影响调度策略" | 改为"只影响 `SmartBlockingQueue` 的入队判定；普通队列上无效" |
| 9 | `QUICK-START-5-minutes.md:128` | "框架会自动选择最优调度策略" | 同上；并把该条从"下一步"改为"仅在 `SmartBlockingQueue` 上生效" |
| 10 | 各文章里 10+ 处 `.taskType(TaskType.IO_BOUND)` | 默认值、普通池上无效果，纯噪声且强化误解 | 批量删除（`IO_BOUND` + `rejectEnqueue=false` 是默认）；只在演示 `SmartBlockingQueue` 的段落保留 |
| 11 | 23 处文末 `blob/main/...` 测试链接 | 绝对 URL，`main` 未合入时 404，链接检查覆盖不到 | 改为相对链接 `../../../src/test/java/demo/article/XxxTest.java`（`check-local-links.py` 的 `SCAN_ROOTS` 已含 `demo`，改完即自动纳入检查） |

**防漂移机制**（分三层，按性价比推进）

- **L1 静态规则（Phase 1，S）**：`scripts/check-demo-articles.py`
  - R1：含 `.defaultPar()` 的 fenced block 必须同时含 `.defaultPar(ParId`（今天 18 条违规）。
  - R2：禁止出现已删除符号 `SubmitterPool`、`submitterPool`、`TaskGraph`、`Graphs.hasCycle`、`canDeadlock`、`report().stateCounts()`（迁移指南白名单除外）。
  - R3：出现 `TaskType.CPU_BOUND` 的 block 必须同时出现 `new SmartBlockingQueue`。
  - R4：禁止短语"触发 `CallerRunsPolicy`"、"自动选择最优调度策略"、"影响调度策略"。
  - R5：文章 ↔ 测试配对检查，缺配对须在 `demo/docs/zh-CN/articles/.no-test-articles` 白名单（当前应列 `I2`、`I3`、`I4`）。
  - 成本：脚本 ~120 行 + `scripts/tests/test_check_demo_articles.py`（沿用 `scripts/tests/` 既有 fixture 风格），CI `docs` job 一行。
- **L2 片段注入（Phase 3，M）**：文章 "代码" 段由测试源码区域生成，脚本 `scripts/sync-demo-snippets.py --check`。
  - 测试侧标记：`// tag::g4-named-pools[]` … `// end::g4-named-pools[]`（结构化标记，不是解释性注释）。
  - 文章侧：`<!-- snippet:g4-named-pools -->` … `<!-- /snippet -->`。
  - 先只覆盖 8 篇高风险（G4/C1/F1/E2/I3/G1/E1/G5），确认可读性后再全量。
- **L3 站点代码块（Phase 2，S）**：`docs/**` 的示例改用已启用的 `pymdownx.snippets`（`mkdocs/mkdocs.yml:88`）直接引用测试源码区域，零脚本注入；需验证 `base_path` 相对路径与 `--strict` 行为（标"待验证"）。

**验收**：
1. `mvn -f demo/pom.xml test` 绿（已有 21 个 article 测试）。
2. `python3 scripts/check-demo-articles.py` 绿；把任意一篇文章的 `.defaultPar(ParId` 删掉后脚本失败。
3. 逐条"复制 8 篇核心文章的代码段到空 `main`"能编译（或由 L2 保证片段来自测试）。
4. 文章内相对测试链接存在，`check-local-links.py` 绿。

**工作量**：Phase 1 的 18 篇机械修复 + A1/QUICK-START 文案 = 0.5 天；G4/C1/C2/F1/E2/I3 重写 + 对应测试调整 = 1.5–2 天；L1 脚本 = 0.5 天。
**风险**：测试里充斥着 `asFuture().get()` 之类的样板（见 `G4_NamedExecutorPoolTest.java:139-168`），直接回填会显著降低教学可读性 → 因此 L2 只抽"setup + 调用"区域，不整段搬运；文章保留人工解释。

---

### 3.4 英文首用入口（P0-4）

**目标**：英文读者有一条与中文 `QUICK-START-5-minutes` 对等、但**发布在站点上**的入口。

**现状**：
- `docs/zh/index.md:13` 有"5 分钟快速上手"（指向 `demo/docs/zh-CN/articles/QUICK-START-5-minutes.md`），`docs/en/index.md` 无对应行。
- `docs/en/index.md` 也没有 Interactive demo 入口（`mkdocs/mkdocs.yml:15` 有，但站点首页 index 没有）。
- `docs/en/interactive-demo.html` 是 `<html lang="zh-CN">`（`docs/en/interactive-demo.html:2`）。`scripts/check-english-docs.py` 已放行"显式声明自身语言"的 HTML，README 与英文 nav 也已标 "(Chinese UI)"——**标注问题已修**，剩下的只是位置与入口缺失。
- 英文 demo 主类输出全中文（`BasicParDemo.java:43-79` 等 4 个文件，共约 108 行含中文）。

**方案**：

1. 新增 `docs/en/quickstart.md`，并同步新增 `docs/zh/quickstart.md`（把中文现文的"最小示例/超时/并发度/结果"四段收敛为站点版）。结构（中英同构）：

   | 小节 | 内容 |
   |---|---|
   | Install | 一条依赖 + 一行"runtime Java 8+" |
   | The smallest runnable program | 与 §3.1 相同的最小示例（0.4 变体：`valuesOrThrow()` 非受检） |
   | What you just did | 三条契约（阻塞/强制超时/快速失败） |
   | When it fails | 失败分支 2 种写法（见 §3.7） |
   | Blocking and checked exceptions | 一段 + `UncheckedIOException` recipe（见 §3.6） |
   | Where to go next | group / TTL / 诊断 / 停机 / `SmartBlockingQueue` |
   | Demo | 指向 interactive demo，明确 "Chinese UI"，并给 4 条语义的英文一句话摘要 |

2. 导航与索引：
   - `mkdocs/mkdocs.yml` 英文 nav 在 `Documentation: index.md` 之后加 `Quick start: quickstart.md`；中文 nav 加 `快速上手: quickstart.md`。
   - `docs/en/index.md` 顶部加一行 `**New here?** Start with the [5-minute quick start](quickstart.md).`；`docs/zh/index.md` 把现有"5 分钟快速上手"改为指向 `quickstart.md`（原来的 demo 文章保留，作为深入阅读）。
   - `docs/en/index.md` 加 `Interactive demo (Chinese UI)` 行。

3. interactive demo 的处置：**保留 `docs/en/interactive-demo.html` 的现状与 URL**（`https://monadrome.github.io/parallel-in-scope/interactive-demo.html` 已被 README 徽章、README 文档表、两处 nav 引用；移动需要 `mkdocs-redirects` 兜底，收益仅是文件位置更"诚实"）。替代做法是在 `docs/en/quickstart.md` 的 Demo 段用英文复述四条语义，让英文读者不依赖该页。若将来重做，推荐移到 `docs/zh/visuals/`（与 `docs/zh/visuals/*.html` 一致），switcher 里加一条重定向。

4. demo 主类输出：**建议翻译为英文**（Phase 2，S–M）。理由：`demo/*/*Demo.java` 是 `exec:java` 的对外输出，英文 README 直接链接它们；现有测试（`demo/src/test/java/demo/basic/BasicParDemoTest.java` 等）只断言"能跑"，不断言文案，改动安全。中文解释留在中文 README 与文章里。若担心中文读者体验，退路是"输出双语标签"（成本更高、噪音更大，不推荐）。

**验收**：
- `docs/en/quickstart.md`、`docs/zh/quickstart.md` 存在且进入 nav；`mkdocs build --strict` 通过。
- `check-english-docs.py` 通过（英文页面无汉字；interactive demo 因显式 `lang` 放行）。
- 英文读者从 `docs/en/index.md` 三步内到 quickstart；中文读者从 `docs/zh/index.md` 到同一页。
- quickstart 的代码块可运行（纳入 §5.3/§5.4）。

**工作量**：S（页面 0.5 天 / 中英各半）+ demo 输出翻译 S–M（0.5 天）。
**风险**：quickstart 与 demo 的 `QUICK-START-5-minutes.md` 形成两份近似内容 → 用"站点版 = 唯一首用来源，demo 版 = 深入阅读"定位，并在 demo 版首行加"站点版更短，建议先看"。

---

### 3.5 构建前置条件与 JDK 25 引导（P1-1）

**现状**：`README.md:135` 才写 "Build JDK: 25"；`mvn compile` 在 JDK 21 下被 enforcer 直接拒绝（`pom.xml:160-178`，`requireJavaVersion [25,)`，消息为 "Error Prone/NullAway requires building on JDK 25 (LTS); the artifact still targets Java 8 bytecode via release=8."）；仓库无 `mvnw`、无 `.sdkmanrc`/`.mise.toml`/`.java-version`，`.mvn/` 只有 `jvm.config`（Error Prone 的 `--add-exports`）；`CONTRIBUTING.md:87-89` 有文字说明但同样在正文中段。

**建议文案（README 顶部，紧跟 badges 之后）**：

```markdown
## Requirements

| Role | JDK | Notes |
|---|---|---|
| Running the library | **8 or later** | The published jar targets release 8 bytecode. |
| Building or testing this repository | **25 (LTS)** | Error Prone and NullAway run at compile time; the Maven enforcer fails any older build JDK. |
| Maven | 3.9.x | CI builds with 3.9.11. |

`sdk env` (SDKMAN, reads the committed `.sdkmanrc`) selects the build JDK for this checkout.
```

中文同构，并显式写"**不要把构建 JDK 和运行 JDK 混为一谈**：产物是 Java 8 字节码，任何 Java 8 运行时都能加载；JDK 25 只是编译器插件的要求"。

**推荐组合**：

| 项 | 是否提交 | 理由 |
|---|---|---|
| `.sdkmanrc`（`java=25.0.4-tem`，标识符实施时用 `sdk list java` 核对） | ✅ 提交 | 一行、零依赖；`sdk env` 即可切换，与 README 文案对应 |
| `.mise.toml`（`[tools] java = "temurin-25"`） | 二选一 | 与 `.sdkmanrc` 功能重叠；除非维护者本人用 mise，否则不提交，避免两套 pin 漂移 |
| Maven Toolchains | ❌ | 单模块、单 JDK、enforcer 已钉 25；toolchains 只增加 `~/.m2/toolchains.xml` 的环境负担 |
| `mvnw` | 暂不（Phase 3 可选） | CI 在 `release.yml:39-49` 里显式下载 Maven 3.9.11 并校验 sha512，wrapper 会引入第二套版本来源；若要加，用 `mvn wrapper:wrapper -Dmaven=3.9.11` 对齐 |

**安装命令（最短路径）**：

```bash
# macOS (SDKMAN)
curl -s "https://get.sdkman.io" | bash
sdk install java 25.0.4-tem     # 具体标识以 `sdk list java` 输出为准
sdk env                          # 在仓库根目录读取 .sdkmanrc
java -version                    # 期望 25.x

# macOS (Homebrew, 备选)
brew install openjdk@25

# Debian/Ubuntu
sudo apt install openjdk-25-jdk
```

（`openjdk@25` / `openjdk-25-jdk` 的可用性随发行版变化，实施时以官方源为准；本仓库不承诺第三方包名。）

**验收**：
- 在只有 JDK 21 的 shell 里 `mvn -q -DskipTests compile` 报出 enforcer 的原文消息；README 在**该消息出现之前**就说明了原因。
- `sdk env` 后 `mvn -q -DskipTests compile` 通过（本机已确认 JDK 25.0.4 下 `compile` 通过）。
- `CONTRIBUTING.md` 的 "Set up and verify locally" 段与 README `Requirements` 表措辞一致（避免两处说法漂移）——建议 CONTRIBUTING 直接链接 README 该节。

**工作量**：S（0.5 天）。
**风险**：`.sdkmanrc` 钉到 `25.0.4-tem` 会随 JDK 25 小版本发布而过时 → 用 major 版本文案（"25.x LTS"）与 `.sdkmanrc` 的精确标识分离；更新责任写进 release checklist。

---

### 3.6 同步阻塞与受检异常（P1-2）

**现状**：`Par.map` 的 Javadoc 已经写了等待语义（`Par.java:98` "This call waits for all results, ignoring interruptions while waiting and restoring the flag"），`docs/en/user-guide.md:59-60` 也写了，**javadoc 和 guide 都不缺**；缺的是首屏。

**README 首屏加两处（已并入 §3.1 的示例结构）**：

1. 契约第一条："**`map` blocks.** It returns only after every element settles (including the bounded cleanup wait). Interrupting the calling thread does not cancel the batch."
2. 示例之后一段 + recipe：

```java
TaskBatchResult<Price> prices = io.map(skus, sku -> {
    try {
        return client.fetch(sku);
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);
    }
}, BatchOptions.timeout("prices", Duration.ofSeconds(3)));
```

并注明版本差异：`0.3.x` 上 `valuesOrThrow()` 把该 `UncheckedIOException` 作为 `ExecutionException.getCause()` 抛出；`0.4` 上非受检异常原样抛出、受检异常包 `CompletionException`（见 `docs/en/migration-v0.4.md:77-114`）。

**链接目标**：`docs/en/user-guide.md#integration-recipes`（现有客户端超时配方 `:666`）、`docs/en/testing/testing-your-code.md`（现有，用于"如何测试自己的超时/取消"）。

**是否改 `Par.map` Javadoc**：**不需要**。现有 Javadoc 已明确"waits for all results"且说明中断策略；再补一句会造成"阻塞语义有两处事实源"。只需在 README 引用它。

**验收**：首屏出现 "blocks" 字样与 recipe；recipe 在 0.3/0.4 下各编译一次（本轮已各编译通过，见 §8.1）。
**工作量**：S（0.25 天）。

---

### 3.7 失败路径（P1-3）

**现状**：README 只 `System.out.println(item.outcome())`（`:51-53`），没有 `valuesOrThrow()` 也没有失败分支；`TaskOutcome` 在 **中英两份 user guide 里完全没有出现**（本轮 grep 核实），只在 Javadoc 里。

**首屏展示两种写法**（README 一段，quickstart 一段）：

```java
// Aggregate read: throws the first recorded failure.
try {
    List<Integer> squares = result.valuesOrThrow();
} catch (ExecutionException failure) {          // 0.3.x
    LOGGER.log(Level.WARNING, "batch failed", failure.getCause());
}

// Per element, without throwing: every non-success carries a throwable.
for (ImmediateResult<Integer> item : result.results()) {
    if (!item.successful()) {
        LOGGER.log(Level.WARNING, "element failed: " + item.outcome(), item.failure());
    }
}
```

版本对照（放进 quickstart 的 "When it fails" 小节，用一句话 + 链接，不铺开）：

| 读法 | `0.3.x`（stable） | `0.4.0-SNAPSHOT`（next） |
|---|---|---|
| 聚合读取 | `valuesOrThrow()` **受检** `ExecutionException` | `valuesOrThrow()` / `orThrow()` 非受检；checked 失败包 `CompletionException` |
| 首个执行失败 | `report().firstException()`（可能为 `null`） | `failedTaskResult()`（`Optional`，纯超时为空） |
| 计数 | `report().stateCounts()` | `outcomeCounts()` |

本轮实测（0.4）：`valuesOrThrow()` 抛 `IllegalArgumentException: bad 3`；`failedTaskResult=USER_FAILURE java.lang.IllegalArgumentException: bad 3`；`outcomeCounts={SUCCESS=3, USER_FAILURE=1}`。0.3 实测：`valuesOrThrow` → `ExecutionException cause=java.lang.IllegalArgumentException: bad 3`，`stateCounts={SUCCESS=3, USER_FAILURE=1}`。

**`TaskOutcome` 表格放哪**：放 `docs/{en,zh}/user-guide.md` 的 `## Immediate results` 段（`docs/en/user-guide.md:259`），8 行表（`SUCCESS` / `USER_FAILURE` / `SUBMISSION_FAILURE` / `MEMBER_CANCELLED` / `GROUP_CANCELLED` / `FAIL_FAST` / `TIMEOUT` / `RUNNING`），每行一句"何时出现 + 该读哪个方法"。README 只保留"非 success 必有 throwable，读 `failure()`"一句话——避免首屏过长。

**验收**：README 与 quickstart 的失败分支代码在 0.3/0.4 下各编译一次（§5.4）；guide 表格与 `TaskOutcome.java` 的 8 个常量一致（脚本可加一条 R6 检查枚举名是否齐全，可选）。
**工作量**：S（0.5 天）。

---

### 3.8 `TaskType` 在首例中的误导（P1-4）

**准确的一句话**（写进 README "What else" 与 guide）：

> `TaskType` only changes whether a `SmartBlockingQueue` refuses to enqueue the task. With any other queue it does nothing and the library warns once per `Par`. It never selects an executor, never changes scheduling, and never triggers a rejection policy by itself — what happens to a refused element is the executor's own `RejectedExecutionHandler`.

（事实来源：`TaskType.java:5-20`、`Par.java:434-466`、`docs/en/user-guide.md:91-117`。）

**改动**：
1. README 首例删除 `.taskType(TaskType.IO_BOUND)`（默认值，且示例用的是 `Executors.newFixedThreadPool`，本来就不生效）。
2. "What else" 里加一行：`Task-type-aware admission (only with SmartBlockingQueue)` → 链 user-guide `#batch` 的 `SmartBlockingQueue` 示例（`:99-117`）。
3. 文章修正清单（本轮全量扫描结果）：

| 文件 | 行 | 现文 | 改为 |
|---|---|---|---|
| `A1-cancel-true-ignored.md` | 33 | "`taskType(...)` 标记为 IO 密集型任务，影响调度策略" | "只影响 `SmartBlockingQueue` 的入队判定；普通队列上无效" |
| `QUICK-START-5-minutes.md` | 128 | "框架会自动选择最优调度策略" | 同上，并注明"需要 `SmartBlockingQueue`" |
| `F1-cpu-task-queuing.md` | 36, 40, 53, 67-71 | "触发线程池的 `CallerRunsPolicy`…零队列延迟" | 见 §3.3 第 5 条 |
| `G4-named-executor-pool.md` | 59 | 普通固定池上用 `CPU_BOUND` | 删除该选项（该文讲命名，不讲准入） |
| 其余 10+ 处 `.taskType(TaskType.IO_BOUND)` | — | 默认值 | 删除 |

4. 测试侧：`F1_CpuTaskQueuingTest.java:116-179` 的 solution 测试目前只断言"并发度 ≤ parallelism+1"和"`options.taskType()` 是我设的值"——后者是同义反复。改为：用 `SmartBlockingQueue` + `CallerRunsPolicy` 的池，断言队列深度始终 0（或 `getTaskCount`/`getCompletedTaskCount` 的单调关系），并断言提交线程确实执行了 body（线程名/id）。

**验收**：`grep -rn "CallerRunsPolicy\|影响调度策略\|自动选择最优调度策略" demo/docs/zh-CN/articles/` 只剩 F1 修正后的正确表述；`check-demo-articles.py` R3/R4 绿。
**工作量**：S（文案 0.25 天）+ M（F1 测试重写 0.5 天）。
**风险**：`F1` 的改写会暴露一个真实语义——`CallerRunsPolicy` 下 body 可能跑在**提交线程**（初始窗口）或**完成线程**（补窗），与 `design/sliding-window-refill.md` 的 borrowed-thread 隔离有关。文章必须写清，否则读者会误以为"CPU 任务永远在池线程上"。

---

### 3.9 文档入口缺失（P1-5）

**README 文档表新增/修改行**：

```markdown
| Entry | Contents |
|---|---|
| [Quick start](#quick-start) | One runnable program and the three contracts |
| [Online documentation](https://monadrome.github.io/parallel-in-scope/) | Published guide for the stable line (currently `0.3.0`) |
| [Javadoc](https://javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0) | Public API reference |
| [Interactive demo](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) | Animated walkthrough of the four core semantics (Chinese UI) |
| [Changelog](CHANGELOG.md) | User-visible behavior per release |
| [Contributing](CONTRIBUTING.md) | Issue-first flow, build setup, verification duties |
| [Demo project](demo/README.en.md) | Runnable examples and the article catalog |
| Development line (`0.4.0-SNAPSHOT`) | `docs/en/index.md` — next-line guide, API differs from `0.3.0` |
```

**Gradle 片段**（放在首屏 Maven 块旁）：

```groovy
// Gradle (Groovy DSL)
implementation 'io.github.monadrome:parallel-in-scope:0.3.0'
```

```kotlin
// Gradle (Kotlin DSL)
implementation("io.github.monadrome:parallel-in-scope:0.3.0")
```

**Javadoc 托管选择**：

| 方案 | URL | 成本 | 结论 |
|---|---|---|---|
| javadoc.io | `https://javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0` | 零维护（自动跟 Central），首次需在站点点一次 "Sync from Maven" | **推荐** |
| Central 构件页 | `https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope` | 零成本，但只能下 jar，不能浏览 | 作为徽章已有目标，不单独列入口 |
| 自建 Pages（`/apidocs/<version>/`） | 自控 | 需在 release 或 gh-pages 流程里增加 `mvn javadoc:jar` + 复制步骤；`pom.xml:235-249` 已产出 javadoc jar，但未发布到站点 | 等做版本化站点时一并考虑 |

本轮核实：`javadoc.io` 当前显示 "No versions in the database yet. Click Sync from Maven to discover versions." → 链接可用，但**需要维护者做一次同步动作**；在此之前该 URL 没有内容，因此 Phase 1 先用 Central 构件页，同步后再切 javadoc.io。这项要写进 §7 待确认。

**验收**：README 表格每行链接可达（javadoc 除外，标注"待同步"）；`check-local-links.py` 绿（`CHANGELOG.md`/`CONTRIBUTING.md` 为仓库内相对链接）。
**工作量**：S（0.25 天）。

---

### 3.10 传递依赖版本说明（P1-6）

**事实**（本轮从 `pom.xml` 与 v0.3.0 tag 读取）：

| 依赖 | 0.4 线 | 0.3.0 | scope | 是否出现在公开签名 |
|---|---|---|---|---|
| Guava | `33.7.2-jre`（`pom.xml:49`） | `33.6.0-jre` | compile | **是**：`BatchOptions.cancelOn(ListenableFuture<?>, …)`、`Task.completionFuture()`、`TaskBatch.completionFuture()`、`ImmediateResult.asFuture()` 等 |
| TransmittableThreadLocal | `2.14.5` | `2.14.5` | compile | 否（仅 `TtlCallable`/`TtlUnwrap` 内部使用），但运行期必需 |
| JSpecify | `1.0.1` | `1.0.1` | compile | 是（`@Nullable` 出现在签名里） |

**建议文案（README "Dependencies" 小节，3–5 行）**：

```markdown
## Dependencies

One declared dependency; three compile-scope transitive ones:

| Transitive | `0.3.0` | `0.4.0-SNAPSHOT` |
|---|---|---|
| Guava | 33.6.0-jre | 33.7.2-jre |
| TransmittableThreadLocal | 2.14.5 | 2.14.5 |
| JSpecify | 1.0.1 | 1.0.1 |

Guava types appear in the public API (`ListenableFuture`), so Guava is a compile-time
requirement; JSpecify annotations appear in signatures. These are the versions the test
suite runs against — no lower bound is verified. If your application pins Guava, check the
resolved version with `mvn dependency:tree -Dincludes=com.google.guava`.
```

**是否做 BOM / `dependencyManagement`**：**不做**。理由：
- 只有 3 个传递依赖，其中 2 个版本几乎不随本库变化；BOM 会把版本治理责任从消费者转移给本库，并需要为新 artifact 增加发布与文档负担。
- Maven 的 nearest-wins 已经让消费者可以用自己的 `dependencyManagement` 覆盖；库这边只需说清"我们测的是哪个版本"。
- 若将来出现"用户必须精确对齐 TTL 版本"的真实案例，再评估 BOM（走 `design/first-principles.md`）。

**下游锁旧 Guava 的实际风险与排查**：
- 风险：解析到比本库编译版本更旧的 Guava 时，库内部调用的方法/类可能缺失 → `NoSuchMethodError` / `NoClassDefFoundError`（典型堆栈落在 `com.google.common.*`）。Guava 还在同一大版本内移除过 `@Beta` API，跨大版本风险更高。
- 排查命令：
  ```bash
  mvn dependency:tree -Dincludes=com.google.guava:guava
  mvn dependency:tree -Dverbose -Dincludes=com.google.common.collect      # 老坐标残留
  gradle dependencyInsight --dependency guava --configuration runtimeClasspath
  ```
- 若要给出**已验证的最低版本**：加一轮矩阵实验（`mvn -Dguava.version=31.1-jre test`、`-Dguava.version=32.1.3-jre test`…），把通过的最低点写进上表。本轮未执行（属"安装/运行新依赖版本"，按仓库权限规则需先确认），标"待验证"。

**验收**：README 表格版本与 `pom.xml` 一致（可加脚本 R7：README 里的版本号必须出现在当前 `pom.xml` 属性中）。
**工作量**：S（0.25 天）；若要立最低版本另加 S。

---

## 4. 分阶段排期

### Phase 1 · 快速修复（1–2 天，先止血）

| 项 | 产出 | 验收 |
|---|---|---|
| README 首屏（中英） | §3.1 结构 + 已验证示例 + 失败分支 + 阻塞说明 | 示例可运行；首屏无未定义符号 |
| 版本通道分离 | §3.2 的链接文案；两处 index 横幅；每页 `docs-version` 标记 | 稳定段只链线上/tag |
| 文档表补齐 | §3.9 表格 + Gradle 片段 | 链接可达 |
| Requirements 上移 | §3.5 文案 + `.sdkmanrc` | JDK 21 失败信息有前置解释 |
| 18 篇 `.defaultPar` | 机械补丁 | `check-demo-articles.py` R1 绿 |
| A1 / QUICK-START / G4:59 文案 | §3.8 表 | R4 绿 |
| `check-demo-articles.py` + 测试 | 新脚本 | CI `docs` job 绿 |

退出标准：一个陌生使用者按 README 首屏 10 分钟内跑出 `SUCCESS:[1, 4, 9, 16]`，并能在同一页看到失败分支。

### Phase 2 · 结构化治理（3–5 天）

| 项 | 产出 |
|---|---|
| 4 篇机制漂移重写 | C1 / C2 / E2 / I3 + 对应测试调整 |
| F1 重写 | `SmartBlockingQueue` + 拒绝策略示例 + 队列深度断言 |
| 英文 quickstart | `docs/{en,zh}/quickstart.md` + nav + index 入口 |
| demo 输出英文化 | 4 个 `*Demo.java` |
| `TaskOutcome` 表 | user-guide `#immediate-results` |
| Javadoc 入口 | javadoc.io 同步后切换；否则先 Central |
| `check-doc-versions.py` + 测试 | 版本/通道门禁 |
| 依赖版本表 | README `## Dependencies` |

退出标准：`mkdocs build --strict`、`check-local-links.py`、`check-english-docs.py`、两个新脚本全绿；中英首用路径对称。

### Phase 3 · 持续防漂移（2–3 天 + 长期）

| 项 | 产出 |
|---|---|
| 片段注入 | 8 篇核心文章用 `tag::`/`snippet:` 注入；`sync-demo-snippets.py --check` 进 CI |
| 站点代码块 | `docs/**` 改用 `pymdownx.snippets` 引用测试源码 |
| 可编译锚点 | `verification/stable-quickstart`（§5.4）进 `java8-runtime` job |
| 可选 | `mvnw` 3.9.11；`mike` 版本选择器评估；Guava 最低版本矩阵 |

退出标准：改动任一示例代码后，不更新文档时 CI 会失败；发布 tag 前 checklist 只剩"人工语义复核"。

---

## 5. 防漂移机制

### 5.1 版本/通道检查——`scripts/check-doc-versions.py`

规则（全部可机械化）：

1. `docs/{en,zh}/**/*.md` 每页首行必须含 `<!-- docs-version: X.Y.Z[-SNAPSHOT] -->`；缺失即失败（迁移指南可用目标版本，白名单显式列出）。
2. 同一 `docs/<locale>/` 下所有标记必须一致（防止"半迁移"：一部分页面写 0.3、一部分写 0.4）。
3. `README.md` / `README.zh-CN.md` 的**稳定段**（`## Quick start` 之前到 `## Development line` 之间）出现的仓库内 `docs/**.md` 链接，其目标页的 `docs-version` 必须等于 README 声明的稳定版本；否则失败，提示"改为线上站点 URL 或 tag URL"。
4. README 稳定段的 `<version>` 必须等于维护者声明的 `stable.version`（脚本常量，或从 `docs/en/index.md` 的横幅解析）。
5. 标注为 `0.3.0` 的页面（或稳定段）不得出现 0.4 专属标识：`TaskKey`、`orThrow()`、`failedTaskResult()`、`outcomeCounts()`、`TaskOptions.timeout(...)` 等；命中即失败并给出对应替代文案。

实现要点：纯 `re`/`pathlib`，无网络，沿用 `scripts/check-design-routes.py` 的结构 + `scripts/tests/` 的 fixture 风格。CI 接入：`.github/workflows/ci.yml` 的 `docs` job，在 "Check local documentation links" 之后加一步 `python3 scripts/check-doc-versions.py`。

### 5.2 文章/测试一致性——`scripts/check-demo-articles.py`

规则见 §3.3 的 R1–R5（+ 可选的 `TaskOutcome` 8 常量齐全检查）。全部是"同一 Markdown 文件内的正则"级别，不做语义解析——与 `design/proposal/P0022-documentation-maintenance.md` "门禁只证明各自覆盖的结构，不扩成通用语义解析器"的约束一致。

### 5.3 代码片段单一来源

- **站点页面**：`pymdownx.snippets`（已在 `mkdocs/mkdocs.yml:88` 启用）直接引用测试源码区域：
  ```markdown
  --8<-- "demo/src/test/java/demo/article/QuickStartTest.java:quickstart"
  ```
  需配置 `base_path` 并验证 `--strict` 下的相对路径行为（标"待验证"）。收益：站点示例零维护，测试通过即示例正确。
- **demo 文章**（不在 mkdocs 体系内，GitHub 渲染）：`scripts/sync-demo-snippets.py`，标记 `// tag::name[]` / `<!-- snippet:name -->`，`--check` 失败于任何漂移。
- 两者共用同一批 `tag` 名称，避免"站点一份、文章一份"。

### 5.4 可编译锚点（新增）

新建 `verification/stable-quickstart/`：
- 一个 20 行的 Maven 模块，`maven.compiler.release=8`，只依赖 `${stable.version}`（默认 `0.3.0`），从 Central 解析。
- 一个 `QuickStartTest`，内容与 README 首屏示例逐行一致（或用 §5.3 的 snippet 注入，使 README 与测试同源）。
- CI 的 `java8-runtime` job 增加一步：`mvn -f verification/stable-quickstart/pom.xml test`（Java 8 运行时）。
- 收益：**稳定版示例永远对着真实发布构件验证**，而不是对着 `dev` 分支的 `target/classes`。这是本轮验证（§8.1）的自动化版本。

> 说明：现有 `verification/maven-central-consumer` 跟踪的是 **next** 线（`0.4.0-SNAPSHOT`，且用到 `TaskKey`），不能用来验证 0.3 首屏；两者互补。

### 5.5 链接检查的边界

- 文章 → 测试的链接改为相对路径后，`check-local-links.py`（`SCAN_ROOTS` 已含 `demo`）自动覆盖。
- 站点 URL 的"线上可达性"不加检查（网络抖动会造成假红）；发布后由 release checklist 人工点一次首页、quickstart、javadoc 三个 URL。
- `check-english-docs.py` 已覆盖 `.html`（按 `<html lang>` 放行），无需扩展。

### 5.6 发布 checklist（人工，3 条）

1. tag 前确认 `main` 已包含该版本（当前本地 `main` 落后于 `origin/main`，见 §8.2），站点构建会跟着 `main`。
2. 确认 README 稳定段版本 = 本次发布版本；Development line 段指向的文档标记 = 下一个 SNAPSHOT。
3. javadoc.io 是否已索引新版本（未同步则补一次 Sync）。

---

## 6. 不建议做的事

1. **不为文档问题改 API**。`unbounded()`、`ThrowingFunction`、Spring Boot starter、queue 包拆分都已被明确否决（`docs/en/design/idea-graveyard.md` §"An unbounded timeout factory" / §"A library-defined throwing mapper" / §"Spring Boot starter"；`adr/0006-queues-ship-with-core.md`）。本方案所有改动都在文档/示例/脚本层。
2. **不新增运行时概念**：时间缝（TimeSource）、运行句柄、request-scoped 观测都不在本次范围（`BACKLOG.md` O-11/§待决策）。
3. **不把 README 切成 0.4-only**。首屏跟稳定版是刻意的：仓库默认分支的访客多数是"想用已发布版本"，把 next API 摆在首屏会**制造**新的版本错配（正是 P0-2 的根因）。
4. **不做 `docs/en/0.3/` + `docs/en/next/` 目录复制**（§3.2 理由）。列表页复制 2 个 locale × N 个版本，长期成本高于收益。
5. **不引入全库代码块编译**。文章片段是"节选 + 业务域符号"（`userService`、`orderIds`），全量编译会产生大量假红；只对"核心 8 篇 + 首屏"做同源注入。
6. **不把门禁当成语义验证**。链接绿、语言绿、片段同源绿，都不能证明"文章的结论正确"（E2/C1 就是语法正确、结论错误）。语义复核仍进 release checklist。
7. **不为 `TaskType` 补诊断或改行为**。`BACKLOG.md` A-P7 记录的"非 `SmartBlockingQueue` 上静默"在 0.4 已有 per-Par WARNING（`Par.java:443-466`）；本次只改文案与示例，不动行为。

---

## 7. 待确认问题（只列需要维护者拍板的）

1. **站点版本策略**：接受"线上站点只服务稳定线 + tag 链接旧版"（本方案推荐，S），还是在 0.4 发布前投入 `mike` 版本选择器（M，需切换 Pages 部署模型并处理深链）？
2. **README 默认代际**：首屏示例继续跟稳定版 `0.3.0`（推荐），还是跟 `0.4.0-SNAPSHOT`？
3. **片段注入范围**：只对 8 篇核心文章 + 首屏做同源注入（推荐），还是 24 篇全量？（全量会把测试里的样板代码搬进文章，损害可读性）
4. **demo 主类输出**：4 个 `*Demo` 是否改为英文输出？（推荐改；中文解释留在中文 README）
5. **Javadoc 托管**：javadoc.io（外部，零维护，需一次同步）还是自建 Pages（自控，需发布流程改造）？
6. **构建 pin 文件**：提交 `.sdkmanrc`（推荐）还是 `.mise.toml`？是否补 `mvnw`（建议 Phase 3 再决定）？
7. **是否要立 Guava 最低版本**：需要一轮 `-Dguava.version=` 矩阵测试（会占用 CI 时间与维护者授权），收益是"下游锁旧 Guava"时能给出确切边界。
8. **`main` 与 `dev/v0.4.0` 文档代际**：0.4 发布时是否需要先把 0.3 文档"冻结"成 tag 说明页（当前 README 的 v0.2 做法），还是直接依赖 git tag？

---

## 8. 本轮验证记录与对交接稿的修正

### 8.1 实际执行过的验证（全部只读；仓库未改动）

| 验证 | 方式 | 结果 |
|---|---|---|
| 0.3.0 最小示例可运行 | `javac`/`java`（Zulu JDK 8.0_432）+ 本地缓存 `0.3.0` jar、Guava 33.6.0-jre、TTL 2.14.5、JSpecify 1.0.1 | `SUCCESS:[1, 4, 9, 16]` |
| 0.4 最小示例可运行 | 同上，classpath 用 `target/classes` + Guava 33.7.2-jre | `SUCCESS:[1, 4, 9, 16]` |
| 文章代码的失败模式 | builder 不设 `defaultPar` 后调 `config.defaultPar()`（0.4 类） | `IllegalStateException: ParRuntime has no default Par` |
| 0.3 失败分支 | 元素抛 `IllegalArgumentException` | `valuesOrThrow -> ExecutionException cause=...bad 3`；`stateCounts={SUCCESS=3, USER_FAILURE=1}` |
| 0.4 失败分支 | 同上 | `valuesOrThrow -> IllegalArgumentException: bad 3`；`failedTaskResult=USER_FAILURE ...`；`outcomeCounts={SUCCESS=3, USER_FAILURE=1}` |
| `UncheckedIOException` recipe 可编译 | `javac` 0.3.0 jar / 0.4 `target/classes`（JDK 8） | 两侧均编译通过 |
| 0.3 API 缺少 0.4 读取面 | 编译 `failedTaskResult()` | 编译失败（0.3 只有 `report().firstException()`/`stateCounts()`） |
| 文章统计 | `grep`/`wc` | 24 篇；20 篇提到 `.defaultPar()`；仅 G1、QUICK-START 有 builder 注册 → **18 篇缺** |
| 已删除机制 | `grep` 源码 | `SubmitterPool`/`submitterPool`/`TaskGraph` 在 `src/main/java` 中不存在 |
| 交互式演示语言 | `head docs/en/interactive-demo.html` | `<html lang="zh-CN">`；`check-english-docs.py` 已按 lang 放行 |
| javadoc.io | `FetchURL` | "No versions in the database yet" → 需一次 Sync |
| 依赖版本 | `pom.xml` / `git show v0.3.0:pom.xml` | 见 §3.10 表 |
| 站点构建来源 | `.github/workflows/gh-pages.yml` | 仅 push `main`；`origin/main` `pom.xml` = `0.3.0`，guide 自述 0.3.0 |

临时验证文件都在 `/tmp/pis-quickstart/`（仓库外），可随时删除。

### 8.2 与交接稿的差异（按当前工作树核实）

1. **文章计数**：交接稿写"20 篇使用 `config.defaultPar()`，19 篇缺 builder 注册"。实际（HEAD `86450971`）：提到 `.defaultPar()` 的 20 篇中，**18 篇**缺 builder 注册（G1 与 QUICK-START 都有）。数量差异不影响结论。
2. **线上站点状态**：交接稿的 P0-2 读起来像"线上站点把 next 当 stable"。实际线上站点由 `main` 构建，而 `origin/main` 是 `0.3.0` 发布态，guide 自述 0.3.0——站点本身自洽；错配在 **dev 分支的仓库内链接**与 **0.4 发布后的旧版可达性**。这决定了本方案选"三通道 + 标记门禁"而不是"立刻上版本选择器"。
3. **额外发现（交接稿未列）**：
   - `C1`/`C2` 正文描述已删除的 `TaskGraph`/`Graphs.hasCycle()`/`canDeadlock()`。
   - `E2`/`I3` 描述已删除的 `SubmitterPool`/`submitterPool`（0.4 改为事件驱动补窗）。
   - 23 处文末 `blob/main/...` 测试链接对新增测试会 404，且不在链接检查范围内。
   - `TaskOutcome` 在中英 user guide 中完全缺失。
   - `F1` 的测试断言是"我设了 `taskType`，所以 `taskType` 是我设的值"，不能证明文章结论。
4. **`docs/en/user-guide.md:3` 的版本横幅**已存在（交接稿引用正确）；因此 P0-2 不需要再从零设计"页面顶部提示"，只需把它扩到 index 与全量页面，并加机械校验。

---

## 附录 A · 本轮引用的关键位置

| 位置 | 用途 |
|---|---|
| `README.md:11-12,17,19-27,29-73,75-86,118-130,132-144` | 稳定横幅、Quick Start、依赖、示例、契约、文档表、构建前置 |
| `README.zh-CN.md:11-13,17-73,118-127,129-135` | 中文同构 |
| `docs/en/index.md:9,13-14` / `docs/zh/index.md:9,13-15` | "Primary guide" 与中文 quickstart 入口 |
| `docs/en/user-guide.md:3,59-60,91-117,259-293,589-751` | 版本横幅、阻塞、`SmartBlockingQueue`、即时结果、集成配方 |
| `mkdocs/mkdocs.yml:12-30,88,108-121,122-169` | nav、snippets、重定向、i18n |
| `src/main/java/.../ParRuntime.java:251-252,289` | `defaultPar()` 异常、`executorWaitReport()` |
| `src/main/java/.../Par.java:98,434-466` | 阻塞语义、失效告警 |
| `src/main/java/.../TaskType.java:5-20` | `TaskType` 的唯一作用 |
| `src/main/java/.../TaskBatchResult.java:85,136,152,166` | 0.4 读取面 |
| `pom.xml:49-50,55,160-178,235-249` | 依赖版本、enforcer、javadoc jar |
| `.github/workflows/ci.yml:12-44,100-150`、`gh-pages.yml:1-20`、`release.yml:39-49` | 文档 job、Java 8 job、站点构建来源、Maven pin |
| `demo/docs/zh-CN/articles/*.md`（24 篇） | 文章清单与修正点 |
| `demo/src/test/java/demo/article/*.java`（21 个） | 可执行事实来源 |
| `design/sliding-window-refill.md` | 补窗机制的权威描述（E2/I3 改写依据） |
| `BACKLOG.md` P-P7 / A-P7 / O-5 | 已删除机制、`TaskType` 静默、演示语言 |
| `docs/en/design/idea-graveyard.md`、`adr/0006-queues-ship-with-core.md` | 不建议事项的依据 |

## 附录 B · 本次未做的事

- 未修改、创建或删除仓库内任何文件（`git status --short` 仍只有交接前已存在的 3 个未跟踪 `design/analysis/*.md`）。
- 未安装依赖、未升级插件、未执行 build/test/PIT、未访问 release/deploy。
- 未对 `verification/stable-quickstart` 落地实现（方案项）。

## 2026-10-29T15:01:32

已产出 P0/P1 首用体验优化方案，详见 result.md
