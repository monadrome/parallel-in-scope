# GitHub About 英文文案候选

## 推荐结论

**推荐候选 1**：它先用一句话说明产品与适用对象，再用具体的失败收敛行为、TTL 上下文和逐元素结果建立差异化，并以 970 个测试及诊断边界同时回应“不是玩具”和“不过度声明”。

## 5 个候选

### 候选 1 — interviewer-oriented（推荐）

Structured concurrency for Java 8+ services running bounded fan-out on existing thread pools. It cancels siblings and unsubmitted work after a failure, preserves TTL context across pools, and gives every element a verdict. 970 tests; executor wait-cycle reports are diagnostics, not deadlock verdicts.

- **字符数**：301
- **取舍**：信息最完整，适合招聘者快速判断技术深度；相比极简版本密度更高，但每句都承担独立信息。

### 候选 2 — pain-first

parallel-in-scope is a structured-concurrency library for Java 8+ services where batch failures can leave work running and pool handoffs can lose request context. It bounds fan-out, cancels siblings and unsubmitted elements after failure, and returns a distinct outcome for every element.

- **字符数**：288
- **取舍**：最适合不熟悉项目的读者，先呈现“工作继续跑、上下文丢失”的真实痛点；没有测试证据和诊断能力，可信度需要由 README 承接。

### 候选 3 — positioning-first

Structured concurrency for the Java 8+ systems you already run, not a general workflow engine. Run finite batches or fixed task groups with bounded fan-out, an explicit or inherited deadline, and fail-fast cancellation. Executor wait-cycle reports surface starvation risks as diagnostics, not automatic fixes.

- **字符数**：309
- **取舍**：适用边界最清楚，适合希望避免被理解为通用并发或工作流框架的场景；没有直接强调 TTL 和测试结果。

### 候选 4 — evidence-first

A focused structured-concurrency library for Java 8+ finite batches and fixed task groups. It bounds in-flight work, cancels siblings and unsubmitted elements on failure, and gives each element a clear outcome. 970 tests back the concurrency contracts.

- **字符数**：252
- **取舍**：最短地组合核心能力与工程证据，适合突出测试投入；诊断和上下文传播完全留给文档。

### 候选 5 — minimal

Structured concurrency for Java 8+ finite batches and fixed task groups. Bounded fan-out, fail-fast cancellation, TTL context propagation, and per-element outcomes, with executor wait-cycle diagnostics limited to structural risk signals.

- **字符数**：237
- **取舍**：便于快速扫读并保留主要能力边界，但第二句仍偏功能摘要，痛点和可信度不如前四个候选鲜明。

## 推荐 topics

按优先级排序：

1. `java`
2. `java8`
3. `structured-concurrency`
4. `concurrency`
5. `concurrent-programming`
6. `cancellation`
7. `cooperative-cancellation`
8. `fail-fast`
9. `thread-pool`
10. `executor-service`
11. `bounded-concurrency`
12. `fan-out`
13. `context-propagation`
14. `transmittable-threadlocal`
15. `guava`
16. `listenablefuture`
17. `task-group`
18. `starvation-diagnostics`

## Homepage 建议

**URL**：https://monadrome.github.io/parallel-in-scope/

**可访问性**：已验证，本次请求最终返回 HTTP 200。它是与项目定位最一致的在线文档入口，比单独链接交互 demo 更适合作为仓库 Homepage。

## README 首屏 3 行 pitch（bonus）

Structured concurrency for Java 8+ services running bounded fan-out on existing thread pools.
Cancel siblings and unsubmitted work after failure, preserve TTL context across pools, and return a clear verdict for every element.
970 tests back the concurrency contracts; executor wait-cycle diagnostics are risk signals, not deadlock verdicts.

## 自查表

- [x] 5 个候选均为单行英文，可直接粘贴到 GitHub About。
- [x] 字符数依次为 301、288、309、252、237，全部不超过 350。
- [x] 候选 1、2、3 即使不了解项目，也能读懂适用对象及至少一个所解决的问题。
- [x] 未出现 `deadlock detection`、`deadlock prevention`、`guarantees no deadlock`、`production-grade`、`battle-tested`、`replacement for Loom` 或其他禁用声明。
- [x] 所有等待环相关表述均限定为诊断或风险信号，没有声称自动修复、自动避免或证明死锁。
- [x] 仅使用交接材料中已核实的事实；测试数字准确保留为 970，没有写成 1000+，未添加用户、采用率或性能数据。
- [x] 每个候选至多使用一项工程证据；未堆砌测试、构建和发布数字。
- [x] Homepage 已实际检查为 HTTP 200；未修改仓库文件，未运行测试、构建或格式化命令。

## 附：主编推荐版本（本会话在候选 1 上润色，294 字符）

> Structured concurrency for Java 8+ services that need bounded fan-out on existing thread pools. A failed element cancels its siblings and unsubmitted work, TTL context survives pool handoffs, and every element gets an outcome. 970 tests; wait-cycle reports are diagnostics, not deadlock proofs.

取舍：保留候选 1 的“定位 + 失败收敛 + TTL + 逐元素结果 + 证据 + 边界”，但把 `running bounded fan-out` 改成 `need bounded fan-out`，把 `verdict` 改成与 API 对齐的 `outcome`，把 `deadlock verdicts` 改成更准确的 `deadlock proofs`。
