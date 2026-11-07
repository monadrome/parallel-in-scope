# parallel-in-scope：工程 / 架构视角评审

评审对象：`/Users/linqh/IdeaProjects/vformation` @ `dev/v0.4.0` / `86450971`（2026-10-29）
评审角色：staff engineer，只看技术可信度，不看叙事包装（那部分由 portfolio / adversarial 两路负责）。
本次所有数字均为我在本机实测（macOS arm64，JDK 25.0.4.1 构建与运行），复现命令见 §8。

---

## 1. 结论

**会信，但要限定范围。**

如果我要在一个已经跑在 Java 8/11、线程池是别人给的应用里做"有界 fan-out + 逐元素归因 + 协作式取消"，我会认真考虑用它；如果目标环境是 Java 21+ 的新服务，我会先问"为什么不直接用 `StructuredTaskScope`"，并且我认为诚实答案是"这个库解决的是一组 STS 不解决的问题，而不是替代它"。

支撑这个判断的硬事实：

- **内核是真的**，不是 Guava 的薄包装。`CancellationToken` 把父子传播收敛进构造函数、deadline 取 min 且一个 token 树共享一个时钟域；`SlidingWindowSubmitter` 用完成监听器自己补窗（每批不占框架线程），并用一把非重入 guard 把内联完成的嵌套回调折叠成循环而不是递归；`ExecutorWaitGraph` 用 CAS 安装整张等待图、只对**新增的**强连通分量发一次告警。这些都是错了就会被踩到的机制，不是命名游戏。
- **测试是拿真线程跑出来的**：970 个测试执行、85 个测试类、测试/主代码行数 2.0:1，85 个测试类里 59 个直接用 `Executors.` 起真池，只有 4 个用 Mockito。deadline 用可控 `ManualClock`（手写 ticker + 手写 scheduler）而不是 `Thread.sleep` 撞运气。
- **可复现性有工程动作**：NullAway + Error Prone 编译期跑，字节码仍 release 8；公开 API 表面被一个测试钉死（`PublicApiSurfaceTest`）、PR 上再与 merge base 做 diff；发布的 jar 在真 Java 8 运行时上跑消费者测试。
- **我说的"但要限定"**：0.x 三个版本全部破坏性（CHANGELOG 中 `0.3.0` 一节 84 条变更），取消是协作式的（CPU 密集必须自己埋 checkpoint），公开性能数字为零，跨 OS/跨运行时未验证；而且对外流传的测试数字（1062）本身就是过期的。

一句话给面试官的话术：**"这是一个把'有界并发 + 逐元素归因 + 协作取消'做到内核级的库，代价是我付了 API churn，收益是我能在 Java 8 上证明它。"**

---

## 2. 最强的技术信号（以及 README 该怎么写）

按"技术含量 / 当前可见度"排序。后两列是给你直接可用的措辞。

| # | 信号 | 证据（可点开核对） | 建议 README 措辞 |
|---|---|---|---|
| 1 | 发布的 jar 在**真 Java 8 运行时**上被验证 | `ci.yml:100-150`：先 JDK 25 构建，再切 JDK 8 跑 `verification/maven-central-consumer` 与 demo 测试 | "The published artifact is exercised on a real Java 8 runtime on every CI run — not just compiled with `--release 8`." |
| 2 | 编译期空安全，字节码仍是 8 | `pom.xml` Error Prone 2.50 + NullAway 0.14.2，`java.release=8`，每个 package `package-info.java` 带 `@NullMarked` | "Null-safety is enforced at compile time (JSpecify + NullAway + Error Prone); the bytecode target is still Java 8." |
| 3 | API 表面被测试钉死 + PR 级 API diff | `PublicApiSurfaceTest` 枚举 31 个顶层公开类型 + 7 个嵌套；`ci.yml:89-98` 调 `scripts/check-api-surface.py` 比对 merge base 编译产物 | "The public API surface is a pinned test and a merge-base diff, so it cannot drift silently in a PR." |
| 4 | 测试规模与形状是健康的 | 970 执行 / 85 类 / 27.2k 测试行 vs 13.8k 主代码行；59/85 类用真执行器，4 个用 Mockito | "970 tests, twice as many test lines as source lines, 70% of test classes drive real thread pools." |
| 5 | 真实竞态的定位 + 回归 | `SlidingWindowClaimRaceTest`：`nextIndex.get() < size` 与 `getAndIncrement()` 之间被 `getAndSet(size)` 插入导致越界，异常还会被 Guava 监听器吞成一条误导性 SEVERE；用 3000 轮真实并发 + 记录 max index 的 List 做断言 | "Race fixes come with a regression that reproduces the interleaving, not just the symptom." |
| 6 | 变异测试是有纪律的，不是摆设 | `design/runner-interrupt-delivery.md:74-84`（56 mutants / 41 killed / 10 survived / 5 no-coverage，逐项分类）；`design/confirmed-proposal-defects.md:73`（21 killed / 2 timeout / 4 survived，存活全部保留为测试缺口）。**我本次的独立 PIT 尝试是 blocked，见 §8，不能算作证据** | "Mutation testing is scoped per change and every survivor is classified in `design/`; it is not treated as a proof of race-freedom." |
| 7 | 诊断机制本身是审慎的 | `ExecutorWaitGraph`：一次 volatile 读 + 表查询；安装成功者比较前后强连通分量，保证每个新环只播报一次；告警走 runtime 自己的线程，绝不在提交线程调 logging handler。`ThreadPoolStarvationObservationScope` 的 javadoc 明确写"长 CPU 任务同样触发该信号，报告不宣称依赖环" | "Cycle detection announces each newly formed strongly connected component exactly once, from a runtime-owned thread." |
| 8 | 文档构建是受检的 | `ci.yml:13-44`：英文语言检查、本地链接检查、design 路由表检查、脚本自身 unittest、`mkdocs build --strict` | "Docs are built with `--strict` plus link, language, and route-table checks; the checkers have their own unit tests." |
| 9 | 外部消费者是真外部 | `demo/` 独立 Maven 工程、`project.version` 从命令行注入，CI 传入刚构建的版本；demo 测试含 A1–H1 的文章级用例 | "The `demo/` project is an independent Maven build that consumes the artifact by coordinates, so it fails if the published API is unusable." |
| 10 | 滑窗补窗不占框架线程，且有反向验证 | `SlidingWindowSubmitter:233-247`：完成监听器自己认领下一个元素，非重入 guard 把内联完成折叠成循环；`BACKLOG.md:401-403` 记录反向验证基线（24 条等待线程 → 0），回归 `SlidingWindowResourceBoundTest`。框架线程恒为 3 条（`ParRuntime:116/121/126`） | "Bounded fan-out costs one guard and one listener per element — no framework thread per batch. The regression was reverse-verified against the pre-fix tree." |

### 建议在 README 增加的一节（可直接粘贴）

```markdown
## Engineering evidence

- **970 tests / 85 classes**, twice as many test lines as library lines. 70% of
  test classes drive real thread pools; deadlines use an injectable clock and
  scheduler, so no test depends on wall-clock timing.
- **The published artifact runs on Java 8.** CI builds with JDK 25 (Error Prone +
  NullAway) and then runs the consumer and demo suites on a real JDK 8 runtime.
- **The API surface is pinned.** A test enumerates every public type, and PRs diff
  the surface against the merge base.
- **Race fixes ship with reproductions.** See `SlidingWindowClaimRaceTest` for the
  claim-range race and `design/` for mutation-testing survivor classifications.
- **Diagnostics are conservative by contract.** Cycle detection announces each new
  component once; starvation reports say what they cannot distinguish.
- **Bounded fan-out costs no framework thread.** The sliding window refills from
  completion listeners under one non-reentrant guard; a runtime owns three threads
  regardless of how many batches are in flight.
```

另外两个**零成本**的可见度修复：

- README 里没有 Javadoc 链接，但 `javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0/index.html` 已经返回 200（我实测）。加一个 badge 即可。
- `docs/en/case-studies/ai-concurrency-review-false-positive.md` 是全仓库最有辨识度的工程叙事（"AI 评审给了个听起来很对的误报，我用 future 状态机加一个 settable-future 测试证伪"）。它现在埋在 docs 第三层，README 完全没提。

---

## 3. 缺口清单

### P0（先做，合计约 1 天）

**P0-1. 开发主线没有 CI（约 10 分钟）**
`ci.yml:3-6` 只监听 `main` push 与 PR；`codeql.yml:5,7` 同样。而所有开发直接 push 到 `dev/v0.4.0`。
`snapshot.yml:118-120` 的注释已经自己承认这一点："the development branch has no continuous test run of its own"。更糟的是它的"Verify the library and the demo"只在**当天首次发布**（UTC 日期门控）时跑，所以一天里其余所有 push 无人验证。

严重性判断：**对旗舰仓库而言这是最扎眼的一条**。不是因为代码会坏，而是因为"绿 badge 代表 main（上一次发布），你真正在写的分支没有门禁"这件事，任何 reviewer 花两分钟就会看出来，而且它恰好否定了你最想展示的"我很在意验证"。

最小修复：`ci.yml` 的 `on.push.branches` 加 `'dev/v*'`（一行），`codeql.yml` 同样。成本是一次完整 CI 的机时。

**P0-2. 对外数字过期（约 1 小时）**
共享上下文与任何准备材料里的 **"1062 tests / 106 classes" 是错的**。我用 `mvn clean test` 实测：**970 tests / 85 classes / 0 失败**，耗时 1 分 24 秒。1062 来自 10-27 的 `target/surefire-reports`，那份报告里还包含已被删除的 `TaskGraph*Test`（同一目录里 182 个测试 class 文件对 86 个源文件）。
在面试里说一个能被 `mvn test` 一秒推翻的数字，代价远大于收益。统一改成 970 / 85，并说明"数字随删测试下降"。

**P0-3. 没有公开的"保证与限制"页（约半天）**
现在最接近的是 `docs/en/user-guide.md:752` 的 Operational rules 与 `reference/cooperative-cancellation.md`，加上中文、钉在旧 commit 上的 `BACKLOG.md`。一个 staff engineer 打开库时第一个找的就是这个。需要一页 `docs/en/limits.md`，至少覆盖：

- **取消是协作式的**：CPU 循环必须自己埋 `Checkpoints.checkpoint()`；`interrupt` 只对阻塞调用有效；deadline 到期后由 checkpoint 观察，不承诺在某一毫秒精确抢占。
- **执行器是借用的**：`ParRuntime.close()` 永不 shutdown 注册的池，`awaitQuiescence` 返回 `false` 也不代表共享资源可释放（README 已写后者，值得进这页）。
- **框架线程是 O(1) 的**：一个 runtime 固定三条 framework 线程 —— 定时器 1 条（`ParRuntime:116`，`removeOnCancelPolicy=true`）、starvation 采样 1 条（`:121`）、超时动作 cached pool（`:126`，daemon，按到期突发增长）。**滑窗补窗没有专用线程**：认领在完成回调线程上跑（`SlidingWindowSubmitter:233-247`），所以"并发批次数 × 线程"这个常见成本在这里不存在——这是一条应该主动说出去的卖点，而不是等别人问。
- **starvation 信号无法区分"依赖环"与"长 CPU 任务"**：javadoc 自己写了（`ThreadPoolStarvationObservationScope` 类注释），文档页要跟上。
- **诊断不是预防**：等待图只记录、只告警，不阻止环形成。
- **`DrainingBlockingQueue` 是单向三态生命周期**：OPEN → DRAINING → DRAINED，不可重开。

### P1（下一轮，合计约 2–3 天）

**P1-1. 没有任何可核对的性能数字（约 1 天，方案见 §4）。**
隐含声明不少："sliding-window scheduling"、"bounded concurrency"、"fail-fast"，但公开仓库里搜不到一个数字（`grep -rniE "throughput|latency|benchmark|jmh"` 只命中 idea-graveyard 的一句设计判断）。而**你测过**：`BACKLOG.md` 记录 `Par.submit` 4578 B/op、2727 ns/op，`Par.map` 每元素 1935 B/op（2.4×），`schedule+cancel` 161 ns，甚至量化了 P-P0 的 76 B/条 定时器残留。这些数字躺在指向 `backup/scratch-materials` 分支的链接里，主线上不可复现。
注意：它们是 2026-09-28 在 0.3.0 线上测的，此后 `bind` 路径和滑窗补窗都改过（`aa37646a`、`8eb9d9c6`、`ba37cd75`），**不能当作 0.4.0 的当前事实发布**。正确做法是用 §4 的探针重测一次，把"环境 + commit hash"连同数字一起公开。

**P1-2. 平台/运行时兼容矩阵太窄（约半天）。**
矩阵只有一个条目（`ci.yml:57` 的 `java-version: [ 25 ]`），OS 只有 `ubuntu-latest`。对一个声称 Java 8+ 的并发库，`linux × {8, 11, 17, 21, 25}` 的消费者冒烟 + 至少 `windows` 一条，是最低要求。并发缺陷对调度器与定时器精度敏感，跨 OS 跑一次比多写 50 个单测更能说服人。

**P1-3. PIT 只在人脑里，不在流程里（约 1 天）。**
profile 配得很好（`pom.xml` pitest profile：`setRemoveOnCancelPolicy` 那类坑都注明了，`VOID_METHOD_CALLS` 排除理由也写了），但 CI 里没有任何 PIT 作业。我在本机尝试了一个 scoped 运行，**结果无效**：4 分 26 秒内出现 3 次 minion `TIMED_OUT`，最慢单个测试 11.1 秒。按仓库自己的规矩，这种运行只能记 blocked，不能当分数用（§8）。
这也说明**不要**把它设成 PR 门禁：它现在的形状是"能跑通就 4 分钟以上、跑不通就超时"。建议周度 schedule，`-DtargetClasses` 只跑内核 3–5 个类，把 `mutations.xml` 的 killed/survived 汇总进 job summary，不 gate。

**P1-4. 测试的"资源形状"没有被断言（约半天）。**
线索（来自那次无效 PIT 的 `mutations.xml`，只能当线索不能当结论）：`SlidingWindowSubmitter.java:123` 的 `remaining <= 0` 变异为 `< 0` 被标为 survivor。语义上是等价的（窗口已覆盖全量时多走一遍 `driver.signal()` 仍然正确），变的是**是否多建一条 signal 链路**。也就是说：现有测试断言了"结果对"，没有断言"没有多余的结构"。这正好是 P1-1、P1-3 的共同缺口，也是别人问"你的 bounded concurrency 到底省了什么"时你会卡住的地方。`SlidingWindowResourceBoundTest` 已经有"修复后 0 条等待线程"这类反向验证的先例（见 `BACKLOG.md:401-403`），把同类断言补到 signal 链路上即可。

**P1-5. `BACKLOG.md` 已经腐烂到会误导人（约半天）。**
它自己钉在 `dev/v0.3.0 @ c9f4c19` 并在头部声明"行号需复核"，但实际状况比声明更差：总览表 `BACKLOG.md:32` 仍把 P-P7（"每批次一个阻塞 submitter 线程"）列为开放，而同一文件 `:401` 已记录该问题在 2026-10-04 修复；代码里也确实没有 `submitterPool` 了（`SlidingWindowSubmitter` 现在是完成事件驱动补窗，javadoc `:25-32` 就是这件事）。这类"文档说有问题、代码说没有"的对照，恰好是 interviewer 最容易被抓的点，而且会被归到**我**头上，不是代码头上。
处置：要么把总览表与文末修复记录对齐并把过期条目移入"已修复"区，要么整篇降级为 `design/internal/` 并明确标注"历史快照，不代表当前代码"。我倾向后者 —— 一份会腐烂的 backlog 不如一份诚实的"已知限制"页（P0-3）。

### P2（有空再说）

- `README.zh-CN.md:120-124` 的文档表缺 v0.4 迁移指南（英文版 `README.md:125` 有）。
- `gh-pages.yml:3-9` 只在 `main` 上构建文档，所以在线站点永远是"上一个稳定版"——这与 P0017 的定位一致，但 README 里"Online documentation"没说明它是**稳定版**站点，读者会以为它跟 `dev/v0.4.0` 同步。
- 可复现构建：加 `project.build.outputTimestamp`，jar 字节级可复现，成本 5 分钟，是可验证的工程品味信号。
- 没有 `known issues` / `changelog` 在 README 的入口链接（CHANGELOG 质量很高，148 条变更写得很实，但只在仓库根部）。
- 测试有 79 处 `Thread.sleep`（分布在 28 个文件）。大部分是必要的存活/让位断言，但如果要更硬，可以加一个 `-Pstress` profile 把竞态类按固定 seed 重跑 N 次，而不是在日常 CI 里随机化。

---

## 4. 最小可信 benchmark 方案

原则：**只测调度开销，不测 IO**；**只报比值，不报绝对值**（CI 机器不可信）；**不进 PR 门禁**。

结构：`benchmarks/`（独立 Maven 工程，像 `demo/` 一样不被发布，不进主 pom 的 reactor），JMH 1.37，`@Fork(2)`、`@Warmup(3×1s)`、`@Measurement(5×1s)`、`-prof gc`。

四个用例，覆盖所有隐含声明：

| 用例 | 测什么 | 对应隐含声明 | 关键对照基线 |
|---|---|---|---|
| `B1 submitSingle` | `Par.submit` 单任务端到端 ns/op 与 B/op | "单任务也能用" | 裸 `directExecutor.execute(noop)`（同一 JVM 内，报比值） |
| `B2 mapElements` | 1000 个 no-op 元素，parallelism ∈ {1, 8, 不限} | "sliding-window 有界且不贵" | `B1` 的每元素值，证明批量摊薄 |
| `B3 windowHandoff` | parallelism=1 vs =N 的每元素成本差 | "补窗在完成线程上跑，不额外占框架线程" | 两者之差 = 认领 + 交接成本 |
| `B4 checkpointCost` | 热循环里 `Checkpoints.checkpoint()` 每次开销 | "可以在热路径埋 checkpoint" | 空方法调用 |

发布方式：`docs/en/benchmarks.md`，写清机器（CPU 型号 / core 数 / OS / JVM 版本 / commit hash），表格只给 `mean ± CI` 与"相对基线倍数"，并附一行 `mvn -f benchmarks/pom.xml clean package && java -jar ...` 的复现命令。CI 里放一个 `workflow_dispatch` 的 job 只负责"能跑通"，不比较数字。

**更便宜的第一步**：如果不想现在引入 JMH，把 `BACKLOG.md` 里已有的四组数字（2727 ns/op、1935 B/op、2.4×、161 ns）连同探针源码放进 `docs/en/benchmarks-2026-10.md`，标明测量环境与不可回归到主线的说明。半天内就能让"有性能意识"从不可见变成可核对。

---

## 5. CI / 发布加固清单

| 级别 | 项 | 位置 | 工时 |
|---|---|---|---|
| P0 | push 触发加 `dev/v*` | `ci.yml:5` | 5 min |
| P0 | CodeQL 同样加 `dev/v*`（或改成所有分支） | `codeql.yml:5,7` | 5 min |
| P0 | 对外数字改成 970/85 | README / 任何口语材料 | 1 h |
| P1 | 兼容矩阵：`{ubuntu, windows} × {8, 17, 21}` 只跑消费者冒烟 | `ci.yml:100` 复制 | 2 h |
| P1 | 周度 PIT（schedule，3–5 个内核类，只发 summary） | 新 workflow | 2 h |
| P1 | 资源形状断言（补窗 signal 链路、框架线程数） | 新测试 | 3 h |
| P1 | `mvn verify` 全量而非只用 `install`（现在 `ci.yml:78` 是 `clean install`，`verify` 会跑 package 校验） | `ci.yml:78` | 10 min |
| P2 | `project.build.outputTimestamp`（可复现 jar） | `pom.xml` | 10 min |
| P2 | Javadoc 构建加 `failOnWarnings=true`（现在 `pom.xml:239` 是 `false`） | `pom.xml` | 2 h（要清警告） |
| P2 | README 加 javadoc.io badge | `README.md:5-9` | 5 min |
| P2 | release 流程加"发布后对 Central 再跑一次消费者"（`scripts/verify-maven-central.sh` 已有，接到 `release.yml`） | `release.yml` | 1 h |

不建议做的：把 PIT 或覆盖率设成 PR 门禁（并发库的覆盖率与正确性相关性低，且会把 CI 拖到 5 分钟以上）；给 snapshot 加"每次 push 都验证"（现在按 UTC 天门控是合理的省机时策略，问题在**别的** workflow 没兜住）。

---

## 6. 五个硬问题 + 参考答案

### Q1. "这不就是 Guava `ListenableFuture` 加 TTL 的一层包装？"

**参考答**：包装不会关心这四件事，而这是代码里真正花时间的地方。
1. `CancellationToken` 把父子传播收敛进**构造函数**（`CancellationToken:100-140`），`bind()` 只做"截止时间 + fail-fast"接线；父 token 的终态立刻把子 token 推到 `PROPAGATED_CANCELLED`，且显式 `cancel(false)` 不中断、deadline/fail-fast 必中断——这套语义差异如果散在调用点，就会出现"有时中断有时不中断"的不可复现 bug。
2. 有效 deadline 是 `min(请求值, 父值)`，且一个 token 树只有**一个时钟域**，子 token 继承父的 ticker 与 scheduler；这是"嵌套作用域不会互相等待不同时钟"的前提。
3. 所有入口都**先 bind 再提交**（`Par.submit` / `Par.map` / `TaskGroup.start`），因为提交可能在内联执行器上同步跑 body，而 deadline 是那条路径唯一的存活保证。
4. `ExecutorWaitGraph` 的增量环检测：CAS 安装新状态，安装成功者比较新旧强连通分量，保证每个新形成的环只播报一次，并且播报走 runtime 自己线程（不在提交线程调 logging handler，避免阻塞或重入）。

### Q2. "Java 21 有 `StructuredTaskScope`，为什么还要这个？"

**参考答**（诚实版，别硬吹）：STS 给的是"作用域 + join + shutdown"，它不解决我这几个问题：**有界 fan-out**（一次 5000 个元素、只允许 8 个在飞）、**逐元素归因**（8 种 outcome：`USER_FAILURE` / `TIMEOUT` / `FAIL_FAST` / `GROUP_CANCELLED` …，以及"这个元素到底跑没跑"）、**跨执行器的等待图诊断**、**基于 TTL 的上下文传播**、以及"timeout 是强制选择"（`BatchOptions` 只有 `timeout(name, d)` 与 `inheritTimeout(name)` 两种，没有"忘了配就不设超时"这个第三态）。
如果面试官追问"那我在 Java 21 上该用哪个"——答：**新代码默认用 STS；当你要的是有界 fan-out 与逐元素归因，用这个。** 顺带说清代价：Java 8 字节码 + Guava 依赖，是给存量系统的，不是给新服务的。

### Q3. "1062 个测试——不，970 个，它们是有意义的还是堆数量？"

**参考答**（带本次实测证据）：测试/主代码行 2.0:1；59/85 个测试类起真线程池，只有 4 个用 Mockito；deadline 用可控 `ManualClock`（手写 ticker + 手写 scheduler，测试里推进时钟只会触发定时任务，body 仍在真线程上跑并被真中断）。另有**无法从外部 gate 的竞态**用 3000 轮真实并发打（`SlidingWindowClaimRaceTest`），断言用"记录 List 被请求过的最大下标"绕过 Guava 吞异常。
变异测试这条要**如实说**：我尝试跑一个 scoped PIT，minion 三次 `TIMED_OUT`，所以那次运行不能作为通过证据（详见 §8）；仓库里已有的可靠记录在 `design/` 里（例如 56 mutants / 41 killed，存活逐条分类）。
**主动承认的缺口**：没有覆盖率报告、PIT 不在 CI、跨 OS 未验证。

### Q4. "高并发下会不会把自己饿死？你的并发论证是什么？"

**参考答**：分三层。
1. **不制造环**：同步嵌套（body 里再调 `map` / `runAll`）跨不同执行器时确实可能形成环；库**不阻止**，而是记录"哪个已注册执行器在等哪个易饥饿执行器"，形成环时从 runtime 自己的线程打一条 WARNING，`executorWaitReport()` 给出环成员、一条具体环、以及 witness。这是诊断，不是预防——javadoc 与文档都这么写，不能吹成"死锁自动解决"。
2. **不假装能精确识别**：starvation 观测的判据是"无空闲 worker + 队列非空 + 完成计数在相邻采样对之间不变"，javadoc 明确写了长 CPU 任务会产生同样信号。
3. **框架线程是常数，且我自己验证过**：一个 runtime 固定三条 —— 定时器 1 条（`ParRuntime:116`）、starvation 采样 1 条（`:121`）、超时动作 cached pool（`:126`）。滑窗补窗**没有专用线程**：首次窗口在调用线程上提交，之后每次完成事件的监听器自己认领下一个元素，用一把非重入 guard 串行化，内联完成（direct executor / 饱和的 CallerRunsPolicy）会把嵌套完成折叠成持有者循环的迭代而不是递归（`SlidingWindowSubmitter:233-247`）。这条是 2026-10-04 修掉的（`design/sliding-window-refill.md`，回归 `SlidingWindowResourceBoundTest` 反向验证：修复基线 24 条等待线程 → 0）。
   剩下的真问题是**定时器粒度**：deadline 由单线程 timer 触发，到期后的中断投递在 cached pool 上；大并发同时到期会有一次突发。这个我还没有量化，诚实说法是"线程数是 O(1)，但到期突发时的动作线程数我没测过"。

### Q5. "三个 0.x 版本全是破坏性变更，我凭什么在生产里用它？"

**参考答**：0.x 的语义是"API 还会动"，我把它当作**显式契约**而不是意外：每个破坏性版本都有等长的迁移指南（v0.2 / v0.3 / v0.4 各 295 / 250 / 242 行），PR 上有一个 API 表面 diff gate（`check-api-surface.py` 对 merge base），并且我**主动删过**不可达的公开类型（`CancellationToken` 在 0.4 包私有化，理由：没有任何公开成员接受或返回它，用户 `new` 出来 `cancel()` 什么都不做）。
诚实的另一面：1.0 的标准是"公开读取面收敛 + 至少一个外部生产用户"，这两条我现在都不满足，所以我不标 1.0。

### Q6. "为什么自己在库里手写 BlockingQueue？你怎么知道它是对的？"

**参考答**：那是从 JDK `LinkedBlockingQueue` 移植语义之后加的"单向三态排空"（OPEN → DRAINING → DRAINED），契约在 `design/draining-queue-contract.md`：规则优先级瀑布（先参数校验 → 存量优先 → 拒绝生产者 → 终态消费者 → mutation 策略）、poison 是虚拟终态信号而不是元素、拿到最后一个真实元素返回时 `drained()` 已经为真。验证方式是 152 个队列测试，含"每个方法在每种状态下的行为"的笛卡尔矩阵与 JDK 移植一致性对照（`DrainingBlockingQueueContractTest` / `DrainingBlockingQueueTest` / `VariableLinkedBlockingQueue*`）。
**该承认的**：没有 jcstress 那类形式化的线性化验证；`remove(Object)` 的"锁内快照—锁外 equals—锁内按身份确认"两趟遍历是刻意的，不是冗余。

---

## 7. 该砍、该移、该避免

**砍（或至少降级）**
- 所有材料里出现的旧数字（1062 / 106），统一到实测。
- README 第一屏的"0.3.0 是最新稳定版"与 Quick Start 里 `0.3.0` 坐标、正文引用 `0.4.0-SNAPSHOT` 迁移指南——这个混搭是对的（P0017 已定），但要在第一屏用一句话说清"稳定版 0.3.0 / 开发线 0.4.0"，否则读者会以为示例跑不了。

**移（不是删）**
- `design/`（5,156 行）+ `adr/`（1,160 行）+ `AGENTS.md` + 中文 `BACKLOG.md` 是**真的严谨**，但也是"文档体量 > 用户体量"这个质疑的直接来源。处理方式不是删，而是：
  (a) 加 `design/README.md` 一屏索引，第一句说明"这些是工程决策记录，不是用户文档；入口在 `docs/en/`"；
  (b) `BACKLOG.md` 目前钉在 `dev/v0.3.0 @ c9f4c19`，行号早已失效，且是中文——对外部读者是噪声。要么翻成英文并与当前 HEAD 对齐，要么移到 `design/internal/`；
  (c) `todo.md`（根目录 tracked）属于同类，移进 `design/internal/`。
- **不建议**把 `queue` 包拆成独立 artifact：ADR-0006 已经把边界定死，拆分会引入双 artifact 版本耦合，收益（"焦点更集中"）小于代价（"用户要多引一个依赖"）。真正的焦点问题是**叙事**：README 的 Core Capabilities 第 5 条已经提到队列，但读者看不出"这是一个独立的第二产品线"。加一句"此外还提供两个生产可用的队列实现（`DrainingBlockingQueue` / `VariableLinkedBlockingQueue`），与内核无依赖"即可。

**避免过度工程**
- 不要为了"看起来完整"加覆盖率高门槛或 PR 级 PIT：对并发库来说覆盖率跟正确性相关性很低，而 PIT 会把你 1 分 24 秒的测试套件变成 5 分钟以上，反而让真正的信号（快反馈）消失。
- 不要再扩大公开面。目前 31 个顶层公开类型 + 7 个嵌套、283 个公开方法，`TaskGroupResult` 一个类就有 23 个公开方法、8 条读结果的路径（`results()` / `members()` / `resultAt` / `resultOf(String)` / `resultOf(TaskKey)` / `terminal()` / `terminalResult()` / `valuesResult()`）。`design/decision-log.md` 已经逐一论证过"删任何一个都删掉一种合法写法"——我同意**不重开这个决定**，但代价是读者必须被引导：在 user-guide 里明确标出"默认读法"（`valuesOrThrow()` + `resultOf(key)`），其余作为进阶。
- 不要现在标 1.0。

---

## 8. 本次实测记录（可复现）

环境：macOS 15 (arm64)，JDK 25.0.4.1（Homebrew，`JAVA_HOME` 显式指定），默认 JVM JDK 21.0.5，Maven 3.x。

```bash
cd /Users/linqh/IdeaProjects/vformation
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home

# 1) 全量测试（必须 clean：陈旧的 target/test-classes 里残留已删除的 TaskGraph*Test.class，
#    不 clean 会在 surefire 发现阶段直接 NoClassDefFoundError: TaskGraphData）
mvn -B -ntp clean test
# → Tests run: 970, Failures: 0, Errors: 0, Skipped: 0 — Total time: 01:24 min (BUILD SUCCESS)

# 2) scoped 变异测试 —— 本次记为 BLOCKED，不作为通过证据
mvn -B -ntp -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=io.github.monadrome.parallelinscope.SlidingWindowSubmitter' \
  '-DtargetTests=io.github.monadrome.parallelinscope.SlidingWindow*Test'
# → 进程最终 BUILD SUCCESS 并产出 target/pit-reports/mutations.xml，
#   但运行期间出现 3 次 "Minion exited abnormally due to TIMED_OUT"，耗时 4:26，
#   最慢单测 11.1s。按仓库自身规则（AGENTS.md "Adversarial Review"：
#   "A PIT run that started but timed out ... is not a pass"），
#   本次结果只登记为 blocked/timeout，不重跑、不计分、不写入任何"通过"结论。
#   仅为后续排查留线索（非结论）：XML 中 SlidingWindowSubmitter:123 条件边界与
#   :218 日志 lambda 的返回空串被标为 survivor。

# 3) 计数与形状
find src/test/java -name '*.java' | wc -l      # 86 源文件；85 个被执行（ManualClock 是工具类）
grep -rho '@Test\|@ParameterizedTest' src/test/java | wc -l   # 895 个方法，展开 970 次执行
grep -rl 'Executors\.' src/test/java | wc -l   # 59
grep -rl 'org.mockito' src/test/java | wc -l   # 4

# 4) 外部事实
curl -s -o /dev/null -w '%{http_code}\n' -L https://javadoc.io/doc/io.github.monadrome/parallel-in-scope/0.3.0/index.html  # 200
```

### 验证状态（逐项如实登记）

| 检查 | 状态 | 说明 |
|---|---|---|
| `mvn clean test`（库，970 测试） | **通过** | BUILD SUCCESS，84 秒，0 失败 0 错误 0 跳过 |
| 测试计数与形状（源码静态统计） | **通过** | 895 方法 / 970 执行 / 85 类 / 59 用真池 / 4 用 Mockito |
| scoped PIT（`SlidingWindowSubmitter`） | **blocked（timeout）** | 3 次 minion `TIMED_OUT`；不计分、不作为通过证据，未重跑 |
| `demo/` 测试 | **未运行** | 需先 `mvn install` 并联网；仅阅读了 `pom.xml` 与测试类清单 |
| `verification/maven-central-consumer` | **未运行** | 同上；仅阅读了 `pom.xml` 与 CI 作业定义 |
| Java 8 运行时作业 | **未重放** | 只读 `ci.yml:100-150` 的工作流定义 |
| `javadoc.io` 0.3.0 可达性 | **通过** | HTTP 200 |

未在本机运行的部分不代表有问题，只是本轮没有验证；面试陈述时应说"CI 里每天跑"，不应说"我验证过"。

**未修改任何仓库文件**；`git status` 仅剩交接前就存在的三个 `design/analysis/` 未跟踪文件。
