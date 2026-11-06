# parallel-in-scope：招聘经理 / 技术负责人视角评审

## 1. 结论：会约 45 分钟面试，但不是因为 README 写得好

**会约面。** 对 senior Java backend / concurrency-heavy 岗位，这个仓库已经给出足够强的技术信号：它处理的不是“如何开几个线程”，而是取消能否到达任务、结果落定与任务体退出是否一致、同步等待如何造成跨请求线程池饥饿，以及这些语义如何被确定性测试验证。这些正好适合用来判断候选人的并发功底。

但当前首屏低卖了项目。`README.md:14-17` 只有一段通用能力清单，随后 `README.md:29-73` 立即给出同时包含两个线程池、batch、group、combine、结果读取和资源关闭的 40 多行示例。招聘经理在前 10 秒看不到：

- 哪个真实故障值得做一个库；
- 为什么 Java 8 用户不能直接用 JDK 原生结构化并发；
- 为什么不是 `CompletableFuture` 加少量工具函数；
- 有什么可点击、可复现的工程质量证据。

**最大的低卖点：把“API 使用说明”当成了“项目价值证明”。** 首屏应该先卖一个问题、一个差异化判断和一组证据，再教用户配置 API。

这次约面不代表我已经相信项目有生产规模或性能优势。公开仓库目前只有 1 star、0 fork，也没有可验证的生产采用者；性能结论不能从测试数量推导。面试会重点确认候选人是否真正主导过取舍、能否讲清失败语义，以及是否诚实地区分“个人开源项目”与“生产验证过的系统”。

---

## 2. Top 5 impressions

### 2.1 最强信号：这里有值得深挖的并发缺陷，而不是 CRUD 包装

`CHANGELOG.md:209-211` 记录了 direct executor / `CallerRunsPolicy` 下提交线程被任务体占用、deadline 又尚未布防而永久等待的问题；修复是把 bind 放到 submit 之前，并处理借用线程的中断污染。`docs/en/design/philosophy.md:13-19` 把同一不变量浓缩为 prepare → bind → submit。

这是我第一个想追问的故事：它有具体交错、有错误症状、有根因、有协议级修复，也能检验候选人是否真的理解执行器与取消，而非只会背线程池参数。

### 2.2 第二强信号：结果落定不等于任务体退出，语义足够诚实

`design/synchronous-scope-exit.md:8-23` 明确区分结果确定与 body 退出，承认 Java 不能强停任意 `Callable` / `close`，并用 `unfinishedBodies` 暴露清理预算耗尽，而不是伪造“已经关闭”。

这比“支持超时取消”一句宣传有含金量得多。它说明作者知道协作式取消的边界，适合做资源生命周期、服务关闭和故障恢复的深入讨论。

### 2.3 首屏叙事弱，真正定位反而埋在文档里

`docs/en/design/philosophy.md:5-11` 已经清楚写出三个 recurring Java 8 problems，并说明库是有边界的 batch execution + cancellation + diagnostics，而不是通用异步工作流；`docs/en/design/philosophy.md:40-46` 也解释了刻意不做哪些能力。

这些句子比 README 的能力枚举更能帮助招聘经理判断作者的设计观，却没有进入首屏。README 还把 batch 和 group 两个核心概念塞进同一个 Quick Start（`README.md:44-72`），首次阅读认知负担过高。

### 2.4 工程验证很强，但展示和分支覆盖削弱了可信度

CI 不只跑主库：它构建独立 demo（`.github/workflows/ci.yml:77-87`），还在真正 Java 8 runtime 上运行 public API consumer 和 demo（`.github/workflows/ci.yml:100-150`）。`design/task-graph.md:145-147` 记录了反向验证和 scoped PIT 结果，这比单纯贴测试总数更有说服力。

但 `.github/workflows/ci.yml:3-6` 的 push 只覆盖 `main`，当前开发线 `dev/v0.4.0` 的直接 push 不触发 CI，默认绿色 badge 不能证明正在展示的 dev HEAD。另一个小信号是测试数字基线不一致：共享材料记录全量 1062 tests / 106 classes，而本次只读汇总当前 `target/surefire-reports` 得到 970 tests / 85 classes，显然后者不是同一次全量运行。不要在 README 手工维护精确测试数；让 CI 产物成为唯一事实源。

### 2.5 范围是优势，但包装成了“什么都有一点”

库 + queue + diagnostics + 大量设计文档，对 concurrency 岗位是优势，因为它们共享“让并发失败可控、可解释”的主线。问题不在范围本身，而在 README 把 capability、migration、demo、build 全部平铺（`README.md:108-144`），没有建立主次。

公开元数据也不统一：[GitHub repo API](https://api.github.com/repos/monadrome/parallel-in-scope) 显示 description 是中文、README 是英文优先、homepage 为空，topics 只有 `cancellation`、`concurrent-programming`、`structured-concurrency`。中文 README 的文档入口还少列 v0.4 migration guide（`README.zh-CN.md:116-127`，对照 `README.md:118-130`）。这些不是技术问题，却会让 5 分钟筛选显得未经收尾。

---

## 3. 对范围、语言与“是否像真实项目”的判断

### 范围：保持，重新分层，不要砍产品边界

我的立场是：**范围是 portfolio 优势，不是焦点问题；焦点问题出在展示顺序。**

- 第一层只讲 structured execution：deadline、cancellation、fail-fast、bounded submission。
- 第二层讲 production diagnostics：body exit、executor wait graph、starvation observation。
- 第三层才是 queue、扩展和多语言资料。

queue 不应占据首屏，也无需为了“看起来聚焦”拆 artifact；那是已经关闭的产品边界决策。设计文档和 ADR 也不必删除，它们是面试时的证据库，只是不应与新手入口争夺注意力。

### English / Chinese：英文主入口合理，但必须一致

英文 README 适合更广泛的招聘与开源受众，中文完整文档对国内使用者有价值，双语本身不是负担。当前伤害来自不一致：中文 repo description、英文 README、中文 interactive demo、中文 README 文档表漂移。

建议保留双语，但做到：英文与中文 README 同结构；demo 至少提供英文标签或语言切换；GitHub description 用一句英文定位，或在明确面向国内招聘时使用一行中英双语。不要维护两套不同叙事。

### 什么会让我怀疑是 AI 文档工程

- 文档和 review 记录非常多，但没有首屏可复现的单一事故故事；
- 多轮大幅 API 重设计，而外部采用证据很少；
- 精确测试数、PIT 数字分散在历史文档中，容易被误读成当前全量结果；
- 根目录 `todo.md:1-14` 仍是带日期的内部探路清单，`BACKLOG.md:3-19` 固定在旧基线，容易显得“过程材料比产品更突出”。

**如何 neutralize，而不是伪造证据：**

1. 给三个真实缺陷做可运行的 before/after 复现入口，链接具体回归测试和修复 commit。
2. 把 CI、release、Java 8 consumer、独立 demo 放到首屏，一次点击可验证。
3. 明确说“个人开源项目，目前没有声称生产采用”；不要把 1 star 当成失败，也不要编造用户。
4. 面试现场能画出 cancellation / cleanup 状态边界，并主动讲已知盲区。
5. 保留 `docs/en/case-studies/ai-concurrency-review-false-positive.md:13-37` 这种“AI 结论只是假设，必须用状态机和复现验证”的案例，它比回避 AI 话题更能证明工程判断。

---

## 4. P0 / P1 / P2 建议与工作量

### P0：先做，直接影响是否约面

1. **重写 README 首屏并缩小 Quick Start — 0.5 天**
   - 第一段写问题 + Java 8 目标用户 + 差异。
   - 首个示例只保留一个 `Par.map` 场景；group / combine 放第二个示例或 user guide。
   - 首屏加入 Maven Central、docs、demo、Javadoc 和验证入口。
   - 验收：只看首屏 10 秒，能回答“为谁、解决什么、凭什么可信”。

2. **把一个真实缺陷提升为首页故事 — 0.5 天**
   - 首选 bind-before-submit：3 句话写 symptom / interleaving / fix，链到 `CHANGELOG.md`、ADR 和回归测试。
   - 不要只放架构图；一张“submission 占用调用线程，deadline 尚未安装”的小图或 20–30 秒录屏更有价值。
   - 验收：招聘经理无需读设计全文，就能提出一个高质量追问。

3. **补齐公开 metadata 与 profile 一致性 — 0.5–1 小时**
   - homepage 设为 `https://monadrome.github.io/parallel-in-scope/`。
   - description 与英文 README 使用同一句定位。
   - topics 增加 `java`、`java8`、`thread-pool`、`guava`，保持少而准。
   - profile 只 pin flagship 和 1–2 个互补项目；profile README 用三行说明本人、 flagship、验证入口。不要在多个相似仓库之间分散注意力。

4. **让 CI 状态对应当前开发线 — 0.5 天以内**
   - 将 `dev/v0.4.0` 纳入 push trigger，或确保展示的是经过 PR/merge gate 的 commit。
   - README badge / 验证链接指向实际被展示的 ref。
   - 验收：从首屏点入，能看到当前展示 HEAD 的 library、demo、Java 8 consumer 结果。

### P1：约面转化率与面试质量

1. **增加“Why not StructuredTaskScope / CompletableFuture?” — 0.5 天**
   - 写选型边界，不写“原生方案不行”。
   - 说明 Java 8 支持、结构化取消与诊断价值，也说明新 JDK 项目应重新评估原生能力。

2. **建立单一 Verification 页面 — 0.5–1 天**
   - 汇总全量测试、demo、Java 8 consumer、release、scoped PIT 和反向验证。
   - 每项标明运行命令、适用 ref、范围；不要把某次单类 PIT 65/65 写成全库 mutation score。
   - 测试数由 CI 生成或只写 “1,000+”，避免手维护精确值漂移。

3. **做 2 分钟英文/中文演示路径 — 0.5–1 天**
   - interactive demo 提供英文 UI 或明确双语入口。
   - 首页只放一条主路径：失败 → 取消 siblings → 返回归因结果；诊断作为第二幕。

4. **同步双语文档入口 — 0.5 天**
   - 中文 README 补齐 v0.4 入口，保持 section 顺序一致。
   - 可用简单链接/结构检查防止再次漂移，不必引入重型文档系统。

### P2：有真实材料后再做

1. **性能基准 — 1–2 天**：只有准备公开讨论性能时才做 JMH，并公开环境、负载和基线；当前不要用测试数量暗示吞吐或低延迟。
2. **精选 Engineering Notes — 0.5 天**：从设计文档提炼 3 篇稳定案例，链接完整 ADR；不再增加更多平行入口。
3. **采用者、演讲、外部文章 — 持续积累**：只有真实发生时加入。没有时明确个人项目即可。

---

## 5. README 改写方案

### 5.1 建议的前 10 行（英文正文）

```markdown
# parallel-in-scope

**Structured concurrency for Java 8+ services: explicit deadlines, cooperative cancellation, bounded parallel execution, and executor-wait diagnostics.**

Built for teams maintaining Java 8+ applications that need structured failure and resource ownership without requiring the latest JDK.

[Maven Central 0.3.0](https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope) · [Interactive demo](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) · [User guide](docs/en/user-guide.md) · [Javadoc](https://javadoc.io/doc/io.github.monadrome/parallel-in-scope)

**Engineering evidence:** 1,000+ tests, a Java 8 external-consumer CI job, three published releases, and targeted PIT mutation testing.  
[CI](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml) · [Releases](https://github.com/monadrome/parallel-in-scope/releases) · [Design notes](docs/en/design/philosophy.md)
```

说明：badges 可以保留在这一行之后，不要再用五行 badge 把价值主张推到折叠线以下。精确测试数若要保留，应由当前 CI 生成；否则用上面的保守表述。

### 5.2 后续 section 顺序

1. **The problem**
   - 三句以内：一个失败如何取消其余工作；deadline 如何覆盖提交与执行；嵌套同步等待如何耗尽线程池。
   - 明确适合 Java 8+ 服务中的 bounded batch / fixed task group，不是通用 workflow 引擎。

2. **30-second Quick Start**
   - 只展示 `Par.map`：一个 executor、一个 runtime、一个 timeout、一个 parallelism、一个结果读取。
   - 资源关闭用 try-with-resources 或链接完整 demo，避免首个例子同时教 batch + group + combine。
   - 在示例后直接链到完整可运行 demo。

3. **A failure mode this library prevents**
   - 放 bind-before-submit 的 3 句案例和回归测试链接。
   - 这是 portfolio 与“又一套 Future API”拉开差距的关键 section。

4. **Why not the JDK or CompletableFuture?**
   - Java 8 runtime 是选型前提；`CompletableFuture` 可在边界桥接，但不自动提供本库的结构化 scope、fail-fast sibling cancellation 和执行器等待诊断。
   - 同时承认新 JDK 用户应比较原生结构化并发。

5. **What it provides**
   - 三组即可：Execution、Results & cleanup、Diagnostics。
   - queue 和高级集成放文档，不在首屏与主线并列。

6. **Verification and releases**
   - CI / Java 8 consumer / demo / releases / scoped PIT 一页一链。
   - 不承诺生产采用、绝对无死锁或 timeout 强停线程。

7. **Documentation**
   - 只留 User guide、Demo、Design notes、Migration 四个入口。

8. **Compatibility and License**
   - 保留 Java 8 bytecode、build JDK 25、Apache-2.0；详细构建命令移入 contributing / verification。

---

## 6. 2 分钟与 5 分钟讲解脚本

### 6.1 2 分钟 spoken walkthrough

**0:00–0:20｜问题与目标用户**

“这是一个面向 Java 8+ 服务的结构化并发库。我解决的不是让任务并行，而是并行之后失败如何收敛：deadline、fail-fast、取消传播、上下文和有界提交必须在同一个 scope 里工作。目标用户是不能简单升级到最新 JDK、又需要这些生命期保证的服务。”

**0:20–0:40｜差异化**

“`CompletableFuture` 很适合值组合，但本库拥有的是执行作用域：一个成员失败后，siblings 和未提交元素如何取消，返回时哪些 body 已退出，都要成为明确结果。它不是通用 workflow 引擎，也不试图替代所有 Future API。”

**0:40–1:05｜一个最小例子**

只展示 `Par.map` 和失败结果：“这里显式声明 timeout 和 parallelism；同步返回时拿到冻结结果。第一个业务失败触发 fail-fast，触发者和后续取消者保留不同 outcome，不会都变成同一种 exception。”

**1:05–1:35｜一个真实缺陷故事**

“最典型的 bug 出现在 direct executor 或 CallerRuns：提交动作本身会在调用线程运行 body，如果 deadline 在全部 submit 之后才绑定，body 等待同批后续元素时，后续元素和 timer 都进不来。修复不是再加一个锁，而是把协议改成 prepare、bind、再 submit，并用回归测试覆盖这条交错。”

**1:35–2:00｜证据与边界**

“项目已发布 Maven Central，有独立 consumer demo、真实 Java 8 runtime CI 和针对关键类的 PIT。我也明确它的边界：取消是协作式的，timeout 不等于强停任意代码；执行器图报告的是观察到的结构性风险，不是对所有死锁的证明。这是个人开源项目，我不虚构生产采用。”

### 6.2 5 分钟仓库 walkthrough

1. **0:00–0:40：README 首屏**
   - 用问题、Java 8 目标和验证链接建立上下文，不逐行读 feature list。
2. **0:40–1:20：最小 demo**
   - 跑一个失败触发 fail-fast 的示例，指出触发者、 sibling、never-submitted 元素的 outcome。
3. **1:20–2:20：bind-before-submit**
   - 画两个时间点的交错，展示 `CHANGELOG.md:209-211` 与对应测试；解释为什么普通 thread dump 只看到 `WAITING`。
4. **2:20–3:20：executor wait graph**
   - 用 `design/task-graph.md:7-18` 的跨请求 A→B、B→A 例子，说明为什么 request-scoped 图不够。
   - 主动讲 `design/task-graph.md:55-58` 的盲区，展示诚实边界。
5. **3:20–4:10：同步出口与资源清理**
   - 对照 `design/synchronous-scope-exit.md:8-23`，解释 future terminal 与 body exited 的区别，以及为什么结果里要有 `unfinishedBodies`。
6. **4:10–4:40：验证**
   - 点 CI 的 library/demo/Java 8 consumer，展示 scoped PIT 和反向验证，不报无法追溯的总覆盖率。
7. **4:40–5:00：设计边界与下一步**
   - 用 `docs/en/design/philosophy.md:40-46` 解释不做 retry、workflow、第二套 fluent API。
   - 诚实说明采用度与尚未建立公开性能基线。

---

## 7. 三个必须准备的面试故事

### Story 1：deadline 为什么没有安装——bind-before-submit

- **情境**：direct executor / `CallerRunsPolicy` 让 body 占用提交线程，后续提交无法发生。
- **错误**：deadline 在提交循环之后才 bind，唯一可打破等待的 timer 尚未存在。
- **行动**：把入口协议统一为 prepare → bind → submit，保证 prepared future 从创建起就是 caller-visible 的同一个对象。
- **结果**：deadline 覆盖 submission window，inline body 也能收到取消；回归覆盖 direct / CallerRuns。
- **追问准备**：为什么 future cancel 不等于 body 停止？为什么不能把 CallerRuns 一律禁掉？中断标志如何在借用线程上隔离？
- **证据**：`CHANGELOG.md:209-211`、`docs/en/design/philosophy.md:13-19`。

### Story 2：为什么“完成”必须拆成结果落定与 body 退出

- **情境**：future 已取消，但用户 body 仍在 `finally` 关闭资源；过早复用共享资源会产生二次故障。
- **取舍**：公开调用同步返回冻结数据，但只进行有界清理，不无限等待任意用户代码。
- **行动**：分别表示结果确定和直接 body 退出，清理耗尽时暴露 `unfinishedBodies`，不伪造最终时间。
- **结果**：调用方可以做出资源释放决定，也能在 runtime shutdown 用 `awaitQuiescence` 做全局确认。
- **追问准备**：为什么不公开 async handle？为什么 caller interrupt 不取消 scope？嵌套 body 为什么不由父级确认递归覆盖？
- **证据**：`design/synchronous-scope-exit.md:8-23`、`design/synchronous-scope-exit.md:163-189`。

### Story 3：为什么死锁观测必须是 runtime 级执行器等待图

- **情境**：请求 1 在 A 池等待 B，请求 2 在 B 池等待 A；每个请求内部都无环，但合起来会饥饿。
- **错误模型**：request-scoped task graph 既要求用户记得开启，又无法合并跨请求等待。
- **行动**：只记录真实同步等待，按 executor identity 建 runtime 级只增图；已知边热路径只读，新边冷路径 CAS 原子安装，报告有环强连通分量和一个具体环。
- **结果**：能够报告“曾经观测到的结构性风险”，且不在准入线程调用可能阻塞的 logger handler。
- **追问准备**：为什么只增不减？为什么环不等于正在死锁？CallerRuns、外部 runtime、普通锁有哪些盲区？为什么报告 SCC 而不是所有简单环？
- **证据**：`design/task-graph.md:7-18`、`design/task-graph.md:41-58`、`design/task-graph.md:93-113`。

---

## 8. 可能的 hard questions 与回答要点

### Q1：为什么不用 `StructuredTaskScope`？

**回答要点：** 如果运行时可以采用提供稳定原生结构化并发的最新 JDK，应先认真比较原生方案；本库的明确前提是 Java 8+ runtime。它不是声称原生 API 设计错误，而是在旧运行时提供显式 scope、批量结果、TTL 和执行器等待诊断。构建使用 JDK 25 与产物运行 Java 8 是两件事，CI 用真实 Java 8 consumer 防止混淆。

### Q2：为什么不用 `CompletableFuture` 自己组合？

**回答要点：** 可以组合出部分功能，本库也允许在应用边界桥接；但 future value composition 不会自动形成统一的结构化生命周期，尤其要单独解决 sibling / child cancellation、未提交元素、deadline 覆盖 submission、body-exit 确认和执行器等待观测。价值在这些协议作为一个 scope 一致工作，而不是“CF 不能并行”。参考 `docs/en/user-guide.md:719-739` 和 `docs/en/design/idea-graveyard.md:36-38`。

### Q3：timeout 是否保证线程在截止时间停止？

**回答要点：** 不保证，也不应这样宣传。取消是协作式的：阻塞 I/O 是否响应 interruption 取决于其实现，CPU 循环需要 checkpoint；结果可先进入取消终态，body 仍在退出。库分别暴露结果与 body completion，绝不使用 `Thread.stop` 或伪造资源已释放。见 `docs/en/case-studies/ai-concurrency-review-false-positive.md:9-20` 和 `design/synchronous-scope-exit.md:11-23`。

### Q4：为什么公开 API 是同步 `map` / `runAll`，而不是返回 Future？

**回答要点：** 同步出口让 scope 的所有权、join、cleanup 和冻结结果集中在一个入口，避免调用方忘记等待或清理。内部仍然并行。代价是调用线程会等待，当前设计还在等待期间不响应 caller interruption、退出恢复标志，因此 caller interrupt 不是取消协议；请求取消走显式 `cancelOn`。这是语义取舍，不是“同步一定优于异步”。见 `design/synchronous-scope-exit.md:3-23`、`design/synchronous-scope-exit.md:163-189`。

### Q5：等待图检测到一个环，是否证明已经死锁？

**回答要点：** 不是。它表示运行时曾经观察到结构性等待风险；池可能仍有余量，边也不一定同时发生。反过来报告为空也不证明没有死锁，因为普通锁、外部 Future、未注册或看不透的 executor、其他 runtime 都不在模型内。这个限定正是 `design/task-graph.md:55-58` 的 P9。

### Q6：这套机制性能如何？

**回答要点：** 当前不要用没有公开基线的数字回答“更快”。可以解释结构：等待图已知边热路径只读、仅新边 CAS 安装，且 SCC 避免枚举指数级简单环；但 `design/task-graph.md:140-144` 也明确分配性质未做基准测量。正确回答是展示可测设计与现有 BACKLOG，再提出 JMH 计划，而不是把 65/65 PIT 当成性能证据。

### Q7：为什么 queue 也在这个 artifact，是否说明项目失焦？

**回答要点：** 产品边界已经确定，queue 是独立通用组件并有自己的契约和测试；portfolio 展示时把它放在第二层，不把它混成 structured-concurrency 核心协议。重点看各模块是否有一致的失败语义与验证，而不是用 package 数判断工程质量。

---

## 9. 什么应移出 README，什么应保留但不抢入口

### 从 README 移到 user guide / reference

- `README.md:75-93` 的三条详细契约、group chain 解释和 runtime close 规则：Quick Start 后只留一个“read the execution contract”链接。
- `README.md:95-106` 的 TTL snippet 与旧 0.2 提示：分别放 integrations 和 migration。
- `README.md:120-130` 的九行文档表：压缩成四个主入口，migration 版本在文档站内导航。
- `README.md:132-144` 的完整 build 命令：移到 verification / contributing；README 只保留 runtime 与 artifact compatibility。
- batch + group 双示例拆开；首个例子不要同时介绍 `Tuple2`、`ImmediateResult`、terminal combine 和两个 executor。

### 从 docs 拉上 README

- `docs/en/design/philosophy.md:5-11` 的三个 recurring problems 和窄边界。
- `docs/en/design/philosophy.md:40-46` 的“不做什么”，压缩成一句。
- `CHANGELOG.md:209-211` 的真实故障故事。
- `.github/workflows/ci.yml:100-150` 的 Java 8 consumer 证据，以及 releases / demo / scoped PIT 的可点击入口。

### 不必删除，但要整理层级

- `design/`、`adr/`：保留为实现与面试证据库，从 Engineering Notes 精选三篇进入，不要把所有内部过程都当用户文档。
- `BACKLOG.md`：保留真实未决项是优点，但应更新旧基线和已关闭条目，避免成为首屏事实源。
- `todo.md`：这是内部探路材料，不适合作为 tracked 根目录产品入口；收割有效结论后移出主干或至少不再作为公开导航内容。
- 本地 `tmp/`、scratch HTML、`.DS_Store` 未被跟踪且大多已被 `.gitignore:15-24` 排除，不应误算成公开仓库杂乱，也无需为 portfolio 做破坏性清理。

---

## 最终一句话

**这是一个足以支撑 senior 并发面试的工程项目；现在最需要的不是继续扩功能或补更多文档，而是用首屏的一句话、一个最小示例、一个真实缺陷和一组可复现证据，让招聘经理在 10 秒内看见它已经存在的技术深度。**
