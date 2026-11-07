# 对抗性面试官评审：parallel-in-scope

评审基线：`dev/v0.4.0` @ `86450971`，本地工作树 + GitHub 元数据，2026-10-29。
评审姿态：假设作者在过度声明，直到证据推翻它。只读，未改动仓库。

---

## 1. 结论（Verdict）

**看门面：reject。看代码：interview。**

这句话不是我打圆场的措辞，是两条独立的判断：

- 我只给你 5 分钟、只看 README 和 GitHub 仓库页 —— **reject**。理由：一个 Java 8 上重造结构化并发的库、1 star、0 fork、无 benchmark、无外部使用者、无与 Java 21 的对比，文档却是 14,608 行中文为主的 design/adr/proposal 体系。这个组合在第一印象里的读法只有一个：**自娱自乐的文档驱动项目**。尤其当 README 第 11 行说"最新稳定版 0.3.0"，第 105 行却让 0.2.0 用户去读 v0.2.0 的指南 —— 一个连自己版本都校对不过来的项目，凭什么让人相信它的并发正确性？
- 我翻进代码 —— **interview，而且会给高分**。`src/main/java` 13,798 行，`src/test/java` 27,212 行，测试/实现比 1.97:1。`ExecutorWaitGraph` 是不可变 `State` + `AtomicReference` + CAS 重试安装等待边，用 Guava `Graphs` 算循环分量，并且明确规定"环的宣告必须由形成它的那次安装发出"——这是设计过的，不是拼出来的。`SlidingWindowClaimRaceTest` 用 3,000 轮真实并发去打一个 get/getAndIncrement 之间的 claim-range 竞态，并在 Javadoc 里解释了为什么必须用"记录索引"而不是"断言抛异常"(异常会被 Guava listener 吞掉只打 SEVERE 日志)。`ExecutorWaitReport:23` 和 `docs/en/user-guide.md:496` 主动写下"这是结构性风险信号，不是死锁证明"——**一个作者愿意在文档里承认自己的功能证明不了什么，这是我最看重的一条信号**。

所以真实情况是：**工程是真的，包装是假的**。你现在的 README 在主动把一个 senior 级项目降级成 portfolio artifact。

修复成本很低（第 7 节给 2 小时清单），但如果你不修，你就得祈祷面试官闲着没事去翻 `src/`。

---

## 2. 5 分钟里我会形成的印象

### 立住的（不用改）

| 证据 | 为什么它值钱 |
|---|---|
| `adr/0001-layered-cartesian-concurrency-testing.md` §Context | 它不列功能，它列**同一个机制里互相不等价的状态**："`ExecutionPhase.RUNNING` 只证明 Future wrapper 认领了 `run()`，不证明 body 拿到 CPU 时间片；Future 被取消不证明工作停了。" 能写出这段的人，是真的被这类 bug 咬过。这一份文档的价值超过 90% 生产仓库的全部测试注释。 |
| `src/test/java/.../SlidingWindowClaimRaceTest.java:26-31` | 一个无法外部 gate 的真实 interleaving，用 3,000 轮压测替代"确定性复现"，并且诚实说明为什么要用替代手法。 |
| `ExecutorWaitReport.java:19-27`、`ThreadPoolStarvationReport.java:14-17` | 契约里写"我的证明边界在哪里"。这是 senior 与 mid 的分水岭。 |
| `PublicApiSurfaceTest.java` | 29 个公开类型被反射测试锁住（`src/main/java` 根包 29 个 public 顶层类型、271 个 public 方法/构造器），改名必须过测试。API 表面是被治理的，不是随手的。 |
| `ci.yml` 里的 `check-api-surface.py`、Java 8 消费端验证、demo 作为外部消费者 | 产物级验证，不是"我本地跑过了"。 |

### 崩塌的（必须改）

| 证据 | 为什么它杀伤力大 |
|---|---|
| `README.md:14-15` 全部定位就一句话，紧接着 40 行 Quick Start | 没有"为什么存在"、没有"谁该用"、没有"什么时候不要用"。读者被迫在读 6 个陌生类型（`ParRuntime`/`Par`/`BatchOptions`/`TaskGroup`/`Tuple2`/`ImmediateResult`）时自己猜价值主张。 |
| README / `docs/` 里 0 处提及 `StructuredTaskScope`、虚拟线程、`CompletableFuture` 选型 | 面试官第一个问题就找不到答案。全仓库唯一一次提到它是在内部文档 `design/synchronous-scope-exit.md:136`（一句"与 JDK 25 `StructuredTaskScope.join()` 的分工一致"）。`design/proposal/P0017-oss-adoption-path.md:21-23` 已经写明"README 的短选型段"要做，但**还没做**。你已经知道自己缺什么，却没补。 |
| GitHub 仓库描述是中文，且写着"死锁检测" | 中文描述挡住国际读者；"死锁检测"（deadlock detection）与你自己代码里的 `not a deadlock proof`（`ExecutorWaitReport.java:23`）直接冲突。这是**对外声明与内部契约冲突**，不是措辞问题。 |
| `docs/en/interactive-demo.html:2` → `<html lang="zh-CN">` | 英文文档树里放了一个中文界面。`docs/en/index.md:14` 还自己写着 "(the articles themselves are in Chinese)"。 |
| `README.md` 无任何图（5 个 `![` 全是徽章） | 而 `docs/assets/ownership-layers.svg` 这张 900×610 的三层所有权架构图**存在，但没有任何 Markdown 文件引用它**。图做好了不挂出来，这是最亏的一类问题。 |
| `.github/workflows/ci.yml:3-6` 只在 `push: [main]` 和 `pull_request` 触发 | `dev/v0.4.0` 领先 `main` **52 个 commit**，这 52 个从未跑过 CI。README 第一行挂的 CI badge 覆盖不到你正在展示的代码。 |
| 无 benchmark，`pom.xml` 无 JMH，全仓库无 `Benchmark` | 你卖的是"滑动窗口调度"和"TTL 捕获"，这两个词都在暗示性能，但零数据。 |
| 56 个 remote 分支（7 个 `copilot/*`，`task/tests-opt-85ecb0` 这类哈希分支，`backup/scratch-materials`、`tmp/v0.2.0-working-notes`） | 点开 Branches 页面的观感是"实验废墟"。另外 `main` 上有 12 个 `copilot-swe-agent[bot]` 提交、4 条 message 叫 `Initial plan`。 |
| `todo.md` 在仓库根目录，中文，`# 待拍板与待办（2026-10-27 探路结果）` | 一个被追踪的、中文的、阶段性内部工作笔记，摆在 README 旁边。 |

---

## 3. 三个最致命的问题（含模型答案）

### Q1（致死级）「JDK 21 起 `StructuredTaskScope` 已经 preview 了七八轮，JDK 27 还在 preview。你为什么要在 Java 8 上重造它？给我一段你的库能表达、`CompletableFuture` + TTL + 固定池表达不了、而且真实业务里需要的代码。」

**为什么这题最致命**：它同时攻击"存在理由"和"技术判断力"。你现在的 README 对这道题**零防御**。

**模型答案**（关键：不要打 Java 21，打你客户实际待的地方）：

> 我不跟 `StructuredTaskScope` 竞争，我跟"客户已经写在生产里的 20 万行 Java 8/11/17 代码"竞争。三类事实：
>
> 1. **可用性**：`StructuredTaskScope` 到今天仍是 preview，生产要用 `--enable-preview`，而 preview API 跨 JDK 版本二进制不兼容——这对一个要跟着客户 JDK 升级节奏走的公司是禁用项。JDK 8/11/17 存量更是完全用不上。
> 2. **语义不同，不是包装**：`StructuredTaskScope` 的 scope 是词法块（`try`-with-resources）。我这里 scope 的边界是**一次同步调用返回**：`Par.map` 不返回就说明所有子任务已经落定或已被取消。这解决了"能不能 `join` 完就相信没有孤儿线程"的问题，但代价是我不能表达"scope 中途提前退出继续干别的"。
> 3. **真正非它不可的东西是这个**：给定一个固定大小的池，`SlidingWindowSubmitter` 只把窗口内的任务交给 executor，并且**取消能穿到还没入池的元素**（`viewsFor()` 在入池前就把 view 建好并绑定）。裸 `CompletableFuture` + `ExecutorService` 里，一个被 cancel 的提交请求到不了"还排在 `ArrayBlockingQueue` 里"的那份 work——它要么已经在队列里等着被跑，要么根本没提交。这一点加上 `BatchOptions.timeout` 的"二选一无第三态"（`README.md:77-79`），是 `CompletableFuture` 组合不出来的活性保证。
>
> 什么时候别用我：能上虚拟线程 + 最终版 `StructuredTaskScope` 的新项目，别用我。我的价值在存量。

**这道题你必输的地方**：你还没把这套话写进 README，所以要靠嘴说 3 分钟，而面试官已经先入为主。**写进 README 是 2 小时里性价比最高的一件事。**

### Q2「你 8 个月发了 3 个破坏性版本（v0.2、v0.3、v0.4 三份迁移指南）、1 star。你刚说 JDK 的 API 不稳定所以你要自己造，可你自己的 API 更不稳定。哪一版才是你相信的答案？」

**为什么致命**：这是 Q1 的自指反击，而且完全打中。`docs/en/migration-v0.2.md`、`migration-v0.3.md`、`migration-v0.4.md` 三份并列摆着，是硬证据。

**模型答案**（唯一诚实的答法：承认，然后指出收敛点）：

> 0.2 和 0.3 的破坏性改动不是 API 美学，是**机制理解错了**：0.3 之前每个元素在进入窗口前是一个占位 Future，后来的取消根本到不了"还没有真实线程跑它"的元素。那条路走不通，我删了占位符，改成"创建即绑定"（ADR-0007）。这种改动无法兼容，硬加兼容层只会把错误的机制冻进公开面。
>
> 0.4 之后我没有已知的机制级待推翻项：`adr/0007`（bind-before-submit）、`adr/0008`（`TaskType` 只做工作负载匹配诊断，不参与调度）都是拍板过的边界，`design/decision-log.md` 里 2026-10-27 那一批是**收敛方向**（否决 `unbounded()`、否决公开时钟注入点、统一读取面）而不是新机制。我的版本号低，是因为我不想在机制没定死之前假装 1.0。
>
> 反过来说：如果我当时就上 1.0，那三份迁移指南会变成三个"已废弃但仍要支持"的兼容层，那才是真正的技术债。

这条答案的杀伤力在于：**它把"3 个破坏版本"从缺陷重构成"拒绝用兼容层掩盖设计错误"的证据**。但前提是你真能说清每一版为什么必须破坏——`design/decision-log.md` 给了你这个弹药，你只是没在 README 里用。

### Q3「1062 个测试。其中有多少能在实现里注入一个真实 bug 时失败？给我一个 mutation score，或者说清你为什么不需要它。」

**为什么致命**：数量是 portfolio artifact 最典型的自我安慰指标。而且你的 `AGENTS.md` 自己写了 PIT 的用法（`mvn -o -Ppitest ... -DtargetClasses=...`），说明**你跑过**，但仓库里没有存档的报告。面试官看到"1062 tests"和"Mutation testing available but no report"会得出很糟的推论。

**模型答案**：

> 数字我不辩护，我辩护结构。测试按 ADR-0001 分层：每个机制不是"多写几个例子"，而是把**互不等价的状态维度**做笛卡尔——比如 `CancellationPropagationCartesianTest` 是 parent timing（BEFORE/AFTER child bind）× child work（PENDING/RUNNING），因为"取消在 bind 前到"和"取消在 bind 后到"走的是两条不同的代码路径，而"pending"和"running"的可见效果不同。
>
> 变异测试我按类跑，不跑全量（PIT 对 27k 行测试跑全量没有性价比）。已跑过的类里，survivor 分成两类处理：等价变异体记进设计文档，非等价的补测试。**（注：这里必须给出至少一个真实跑过的类 + 分数，否则这句话是空的。跑一次 `CancellationToken` 或 `ExecutorWaitGraph` 大约 10–20 分钟。）**
>
> 另外我认为有两个维度比 mutation score 更能说明问题：一是 `SlidingWindowClaimRaceTest` 那种无法 gate 的 interleaving 用 3,000 轮压测；二是 `PublicApiSurfaceTest` 把 API 表面锁死，防止改名悄悄发生。

**结论**：这题你现在**只能答一半**。因为它不需要 2 小时——跑一个类的 PIT 并把结果截图放进 README，是 30 分钟的事，优先级应该排在"英文 demo"之前。

---

## 4. 逐条攻击表

| # | 声明（出处） | 为什么弱 | 最小解药证据 | 工作量 |
|---|---|---|---|---|
| 1 | "A structured-concurrency toolkit for Java 8+"（`README.md:14`） | 没有定义"structured"在你的语境里指什么；没有一句话说明与 `StructuredTaskScope` 的关系 | README 里 15 行"何时用 / 何时不要用"表 + 一段 before/after（裸 `CompletableFuture` 版本 → 你的版本），对比**被消除的失败模式**而不是对比 API 数量 | 2h |
| 2 | "thread-pool deadlock and starvation diagnostics"（`README.md:15`） | 与自己的契约冲突：`ExecutorWaitReport.java:23`、`docs/en/user-guide.md:496` 都写"not a deadlock proof"；`docs/en/design/philosophy.md:37` 又写"This is executor deadlock detection"；GitHub 中文描述写"死锁检测" | 一个 40 行可运行的失败注入 demo：同一 `ParRuntime` 上两个固定池互相嵌套 `map` → 打印 cycle 警告 + `executorWaitReport()`；然后明确写出"这证明环存在，不证明此刻阻塞" | 2–3h |
| 3 | "1062 tests / 106 classes" | 数量不是证据；无 mutation score、无覆盖率、无 soak 测试 | 跑一个类的 PIT（`CancellationToken` 或 `ExecutorWaitGraph`），把 survivor 分类写进 README 或 design 文档 | 30min–1h |
| 4 | 「结构化并发」这个词 | 你没有词法 scope（无 `Closeable` scope block），语义是"同步调用返回即全部落定"。这在面试官看来是"函数式批量 API"而不是 `StructuredTaskScope` 的对应物 | 一句话定义 + 指向 `design/synchronous-scope-exit.md` 的取舍论证；不要回避"我为什么不做 `try`-with-resources" | 30min |
| 5 | 14,608 行 `design/` + `adr/` + `AGENTS.md` | 对 senior 是加分，对国际面试官是噪音；且**几乎全是中文**（`design/decision-log.md` 单行上千字中文表格） | 把 3 份最高价值的翻成英文摘要并链到 README：`adr/0001`（测试架构）、`design/task-graph.md`（等待图）、`design/interruption-contract.md`（P1–P4 中断规则）。其余不动 | 3–4h |
| 6 | GitHub 中文 description + `docs/en/interactive-demo.html` `lang="zh-CN"` + demo 文章中文 | 英文文档树里的中文制品，国际读者第一眼就掉头 | description 改英文并去掉"死锁检测"；interactive demo 拆 `demo.html`/`demo.zh.html` 或给英文版 | 2–4h（description 5min） |
| 7 | 滑动窗口 / TTL 捕获（性能隐含声明） | 库本身没写"快"，但这两个机制名在暗示性能，且零数据 | 二选一：① 一个诚实的 microbenchmark（固定池 1k/10k 任务吞吐、window=1 vs unbounded 的 p99）；② 明确声明"本库不主张吞吐，主张取消语义与活性"，然后检查全文有无性能暗示并删除 | 4h / 30min |
| 8 | CI badge（`README.md:5`） | `ci.yml:3-6` 不覆盖 `dev/v0.4.0`，落后 52 个 commit | `on.push.branches: [main, 'dev/**']` | 10min |
| 9 | 56 个 remote 分支 | 观感是实验废墟；`copilot/*` 7 个、`task/*-85ecb0` 哈希分支 | 归档/删除 40+ 个已合并分支（**有损操作，建议先打 `archive/*` tag 或建 `attic` 分支，由 owner 决定**） | 30–60min |
| 10 | `todo.md`（根目录、中文、带日期） | 国际读者看到的是一个内部工作笔记被当成仓库文件 | 移入 `design/` 或删除（内容已被 `BACKLOG.md` 与 `design/decision-log.md` 覆盖） | 5min |
| 11 | 版本自相矛盾（`README.md:11` vs `:105`；`README.zh-CN.md:103`；zh 文档表缺 v0.4 行） | 证明 README 无人校对。一个连版本号都对不上的项目，凭什么让人信并发正确性 | 修 4 行 | 10min |
| 12 | 1 star / 0 fork / 无使用者 | 任何"可用于生产"的隐含声明都没有外部背书（你去 WebSearch 也只有自己的仓库和 Maven 索引页） | 不要造数据。要么真实用一次并写下来（"在 X 场景下跑了 Y 个月"），要么明确说"这是个人项目，未在生产使用"。**Maven Central 有 3 个版本 + demo 作为外部消费者跑在 CI 里，这已经足够，不需要假装有用户** | 0 |

---

## 5. 让怀疑者信服的最小改动集

按"每单位工作量消除多少怀疑"排序。**只做 P0，你的面试转化率会变化；做完全部 P1，几乎没人会再说"portfolio artifact"。**

### P0（合计约 3.5 小时）

1. **README 前 10 行重写**（第 6 节给全文）+ 选型表。这一条消除攻击 #1、#4。
2. **修 4 处版本矛盾**：`README.md:105-106` 改指 v0.3 指南；`README.zh-CN.md:103` 同上；zh 文档表补 v0.4 行；两处 demo 链接对齐。消除 #11。
3. **`ci.yml` 加 `dev/**`**。消除 #8。10 分钟换掉整页 CI 的可信度。
4. **把 `docs/assets/ownership-layers.svg` 挂进 README**。图已经画好了，只是没人引它。消除"无架构图"。
5. **录一个 40 秒 GIF**：`mvn -f demo/pom.xml exec:java` 跑起来 → 触发一个 fail-fast 取消 → 打印 `outcome()`。demo 已经能跑，录屏是纯素材工作。消除"无视觉证据"。
6. **GitHub description 改英文，并删掉"死锁检测"**。消除 #2 的对外部分与 #6 的一半。

### P1（合计约 10–12 小时）

7. `StructuredTaskScope` 对比表 + Q1 那段话写进 README「When to use / When not to」。
8. 死锁诊断的失败注入 demo（攻击 #2 的完整解药）。
9. 跑一个类的 PIT 并把 survivor 分类写下来（攻击 #3）。
10. 英文版 interactive demo。
11. 3 份核心 design 文档的英文摘要（攻击 #5）。
12. 分支清理（需 owner 决定，我不建议 agent 执行）。

### P2

13. benchmark **或**明确的不主张性能声明（攻击 #7）。
14. README 加 javadoc.io 链接（现在没有；`docs/en/index.md` 也没有）。
15. `case-studies/ai-concurrency-review-false-positive.md` 改名或加一句"这是对**本仓库**代码的 AI 评审误报复盘，不是客户案例"。叫 case study 但没有任何第三方，是轻微的过度声明。
16. `todo.md` 移出根目录。

---

## 6. README 改写大纲（含前 10 行）

### 新的前 10 行

```markdown
# parallel-in-scope

[CI] [Maven Central] [Java 8+] [License] [Javadoc]

Bounded, cancellable, deadline-bounded fan-out for the Java you already run. `Par.map` returns
only when every element has settled or been cancelled; a batch cannot run unbounded by omission.
Java 8 bytecode, Guava `ListenableFuture`, no `--enable-preview`.

**Use it when** you have a fixed thread pool, a fan-out of 10–10,000 items, and you need
"all of it finishes, or none of it keeps running" — on Java 8/11/17 where `StructuredTaskScope`
is preview-only and your existing code cannot be rewritten.

**Don't use it when** you can adopt virtual threads and the finalized scope API, or you need a
general workflow engine. [Full selection guide →](docs/en/choosing.md)
```

第一屏剩余部分：**一张架构图**（`ownership-layers.svg`）→ **一个 40 秒 GIF** → 12 行最短可用示例（只出现 `ParRuntime`、`Par`、`BatchOptions` 三个类型）。

### 分节顺序

1. **Why this exists**（3 句：Java 8 存量的取消 / TTL 丢失 / 嵌套死锁）
2. **Two-minute tour**（12 行全流程，只引入 3 个类型）
3. **The guarantees**（4 条，每条一句话 + 契约链接。**保留 `README.md:77-86` 现有的三条契约**——那三段是全 README 质量最高的部分）
4. **What it does not prove**（`ExecutorWaitReport` 的边界、`NO_ISSUE` 不等于无死锁、`TaskType` 不参与调度）。**这一节是你和其他 OSS 项目最大的区别，现在被埋在 user-guide 第 496 行**
5. **How this is tested**（ADR-0001 摘要 + Cartesian 图 + race 测试 + API 表面锁 + PIT 结果）
6. **Choosing**（表：裸 `CompletableFuture` / `StructuredTaskScope` / 本库，各行给"适用条件"而不是"优劣"）
7. **Task groups**（`TaskGroup` + `Tuple2` 放这里，不要塞进第一屏）
8. **Queues**（`queue` 包，一句话 + ADR-0006 链接）
9. **Docs / Demo / Compatibility / License**（合并压缩，现在的表格 9 行太长）

### 必须搬走或删除的

- `README.md:36-72` 那段 Quick Start 一次引入 6 个类型：拆成"最小示例（3 个类型）"+"组示例（后半段）"。
- `README.md:105-106`「Staying on the stable 0.2.0 line?」—— 最新稳定版是 0.3.0，这行是错的。
- 「Structured-concurrency toolkit」这个 headline 不能单独出现，必须紧跟一句你自己的定义。

---

## 7. 5 分钟演示脚本

面试时的顺序。**每一段都以"我知道我的边界在哪"结尾**——这是你唯一无法伪造的 senior 信号。

| 时间 | 展示 | 你说的话 |
|---|---|---|
| 0:00–0:30 | interactive demo 或 GIF | "一次 `Par.map`，超时触发后 fail-fast 取消整批，包括还没入池的元素。注意结果是每个元素的 verdict，不是异常。" |
| 0:30–1:30 | `ExecutorWaitGraph.java:74`（`record`）→ `:188-204`（`install`） | "等待边是不可变 State 上的 CAS 安装。关键是成功那次 CAS 才比较新旧 `cyclicComponents`，只宣告**新形成**的环——否则同一条边会被宣布两次，或者新形成的环永远不宣布。这是并发正确性，不是日志。" |
| 1:30（补一句） | `ExecutorWaitGraph.java:207-214` 的 warning 文案 | "注意这条告警自己写着 `not proof that the waits overlap or that threads are deadlocked now`。我在代码里这么写，却把 GitHub 描述写成'死锁检测'——这是我唯一一处该改的口径。" |
| 1:30–2:30 | `adr/0001` §Context | "我的测试不是堆例子。`ExecutionPhase.RUNNING` 只证明 wrapper 认领了 `run()`，不证明 body 拿到 CPU——所以我把'状态'和'可观测证据'分开建模，然后对不等价的维度做笛卡尔。" |
| 2:30–3:30 | `SlidingWindowClaimRaceTest.java:26-40` | "这是真实的竞态：`nextIndex.get()` 和 `getAndIncrement()` 之间，取消路径的 `getAndSet(size)` 会插进来。这个 interleaving 没法从外部 gate，所以我用 3,000 轮打它，并且用记录索引而不是断言抛异常——因为异常会被 Guava listener 吞掉，只打一条误导性的 SEVERE。" |
| 3:30–4:30 | `docs/en/user-guide.md:496` | "这个功能的名字里有 deadlock，但我在契约里写清楚了：它是结构性风险信号，不是死锁证明。两个真实请求的等待可能被拼成一个环，而带余量的池永远不饥饿。我不想让它听起来比实际强。" |
| 4:30–5:00 | `adr/0007` | "0.4 的核心不变量：所有元素在创建时就绑定 `ExecutionPhaseHintFuture`，取消一个 view 必然到达跑它 body 的线程。没有占位符、没有后置绑定——这是我删掉 0.2 设计的原因。" |

**不要做的事**：不要从 `README.md` 开始讲。现在的 README 会让前 30 秒变成你在解释自己的术语。

---

## 8. 硬化问题清单（含模型答案要点）

| # | 问题 | 答案要点（一句话） |
|---|---|---|
| 1 | 这是 Guava + TTL 的薄包装吗？ | 13.8k 行实现里，`SlidingWindowSubmitter` + `ExecutionPhaseHintFuture` + `ExecutorWaitGraph` + `CancellationToken` 是 Guava 完全没有的东西：Guava 给你 future 原语，不给你"窗口内/窗口外的元素都能被取消"和"等待图的环检测"。 |
| 2 | 为什么不用 `CompletableFuture.allOf`？ | `allOf` 是 fail-**slow**：一个失败，其余照跑，你要自己遍历 cancel，而 cancel 到不了队列里还没跑的 work。而且它不强制 timeout——`BatchOptions` 的"二选一"就是为了堵住"忘了写超时"这个洞。 |
| 3 | 为什么要 `Tuple2` / 编译期 task key？ | `Tuple2` 是为了让 `combine(values -> ...)` 拿到**静态类型**的两个值，而不是 `Map<String, Object>` + 强转。PR #62 正在把 key 从字符串推进到编译期类型，方向是对的。（**注意：如果你自己不确定这个收益，就承认它是权衡，别说成设计胜利。**） |
| 4 | 一次性 group chain（`runAll()` 只能调一次）不别扭吗？ | 故意的：可重复调用的 builder 意味着两次执行共享可变状态，而结构化并发的承诺是"一次声明 = 一次范围"。冻结结果比灵活更重要。 |
| 5 | 你的死锁检测准确率多少？ | 我不给准确率，因为它是**结构性**的：它报的是"这些池之间存在等待环"，这一定为真；它不承诺此刻阻塞。`NO_ISSUE` 不排除死锁（`user-guide.md:551`）。 |
| 6 | 有并发 bug 吗？怎么发现？ | 有，且被我自己的测试抓到过。`SlidingWindowClaimRaceTest` 对应的 F-K3-1 是一个真实的越界索引会被 Guava listener 静默吞掉的 bug。`BACKLOG.md` 里还有带证据分级的已知缺陷。 |
| 7 | 1 star，你凭什么说这值得用？ | 我不说它值得用，我说它解决了我的问题，并且我把"它证明不了什么"写进了契约。要用它的人应该先读 `docs/en/choosing.md`。 |
| 8 | `AGENTS.md:223` 里的 `cmux codexyolo` 是什么？代码是你写的吗？ | 直接答："我大量使用 AI 做实现和独立审查——`AGENTS.md` 里写的就是我的工作流，包括让另一个模型跑对抗审查并留下审计轨迹。人类的判断体现在契约边界、被否决的方案（`design/decision-log.md`）和测试架构（ADR-0001）上。" **不要否认，也不用道歉。** 现在的招聘市场里，"用 AI 但知道在哪用、怎么验证"是加分项；唯一的死法是装作没用。 |

---

## 9. 你应该停止声称 / 应该删掉的东西

**停止声称**

1. **"死锁检测"**（GitHub description）。改成 "thread-pool wait-cycle diagnostics"。你代码里已经写了 "not a deadlock proof"，对外却写 detection —— 这是全线最容易被当场戳的声明。
2. **`docs/en/design/philosophy.md:37` 的 "This is executor deadlock detection"**。与 `ExecutorWaitReport.java:23` 直接冲突。改成与 `user-guide.md:496` 同口径。
3. **"structured concurrency" 裸用**。要么给定义（"同步调用返回即 scope 关闭"），要么就承认你借用了这个词的精神。裸用会被追问到无话可说。
4. **"production ready" 一类的暗示**。你目前没写，保持不写。
5. **"case study"**（`docs/en/case-studies/ai-concurrency-review-false-positive.md`）。它是对自己代码的 AI 评审复盘，不是客户案例。加一句来源说明，或改名 `ai-review-false-positive.md`。

**删掉 / 搬走**

6. `todo.md`（根目录，中文，带日期）。内容已被 `BACKLOG.md` + `design/decision-log.md` 覆盖。
7. `design/analysis/llm-model-selection-blog-*.md`、`llm-usage-selection-guide-*.md`（当前未跟踪的 3 个文件）。这是 LLM 模型选择的笔记，跟本库无关。**不要提交**。
8. 40+ 个已合并的 remote 分支（尤其 `copilot/*`、`task/*-<hash>`、`backup/scratch-materials`、`tmp/*`）。**有损，需你决定**；建议先打 tag 归档。
9. README 第一屏的 `TaskGroup` / `Tuple2` / `ImmediateResult`。不是删除，是**下沉**。

**不要做的**

- 不要买 star、不要造使用者、不要在 README 里写"Maven Central 下载量"之类没有意义的数字。
- 不要为了"看起来稳定"而把版本号跳到 1.0 —— 你的 0.x 是诚实的，问题只在 README 没解释它为什么诚实。

---

## 10. 两小时应急清单（按顺序执行，做完即止）

假设明天面试，只有 120 分钟。**严格按这个顺序，做到哪算哪。**

| 分钟 | 动作 | 消除什么 |
|---|---|---|
| 0–10 | 改 `README.md:105-106` 与 `README.zh-CN.md:103` 的 0.2.0 死链；zh 文档表补 v0.4 行 | 最廉价、最显眼的"没校对过"证据 |
| 10–20 | `.github/workflows/ci.yml` 加 `branches: [main, 'dev/**']` | CI badge 覆盖不到展示中用代码 |
| 20–30 | 把 `docs/assets/ownership-layers.svg` 插进 README 第一屏 | "无架构图" |
| 30–75 | 重写 README 前 10 行 + 加「Use it when / Don't use it when」+ 选型表（用第 3 节 Q1 的答案） | **最致命问题 Q1 与 Q4** |
| 75–95 | 改 GitHub description 为英文，删"死锁检测"；改 `philosophy.md:37` 的 detection 措辞 | 对外声明与内部契约冲突 |
| 95–110 | 录 40 秒 GIF（`mvn -f demo/pom.xml exec:java` → fail-fast → `outcome()`）贴进 README | "无视觉证据" |
| 110–120 | 在 README 加一节「What it does not prove」，从 `user-guide.md:496,551` 抄三句 | 把最强的 senior 信号从第 496 行搬到第一屏 |

**120 分钟里不要碰的**：PIT、benchmark、英文 demo、分支清理、design 文档翻译。这些每一项都要 30 分钟以上，而且做完不如上面 6 条中的任意两条值钱。

---

## 11. 什么会让我说"这是真正的工程，不是作品集"

按说服力排序，前三条你**已经做到了**，只是没展示出来：

1. **文档里写自己证明不了什么**（`ExecutorWaitReport.java:23`、`user-guide.md:496`）。90% 的 OSS 项目不敢写这一段。
2. **测试建模的是状态不等价性，不是功能点**（ADR-0001 §Context）。这是 senior 与 mid 的分水岭，而且这份 ADR 的质量超过我见过的多数生产仓库。
3. **真实 interleaving 的回归测试，带失败机理说明**（`SlidingWindowClaimRaceTest:26-40`，包括"异常会被 Guava listener 吞掉"这种只有踩过才知道的细节）。
4. **能说出三个被否决的方案及理由**（`design/decision-log.md`：`unbounded()` 否决、公开时钟注入点否决、读取面对称性收窄采纳）。这证明不是"想到什么加什么"。
5. **API 表面被测试锁住**（`PublicApiSurfaceTest`），改名需要过门禁。
6. **产物级验证**（Maven Central 三个版本 + demo 作为外部消费者在 CI 里跑 + Java 8 运行时验证）。

反过来说：**如果我把上面 6 条全部删掉，只看 README 和仓库页，我不会面你。** 这就是问题所在，也是唯一的修复方向。

---

## 12. 一句话总结

你有一个 ADR 质量高于多数生产仓库、测试/实现比 1.97:1、契约里主动写明"这不是死锁证明"的项目，和一份会让面试官在前 5 分钟 reject 你的 README。**代码不用改，包装改 3.5 小时。**
