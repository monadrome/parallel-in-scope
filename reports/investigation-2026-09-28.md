# 调研记录：运行时性能与公共 API（2026-09-28）

**分支** `dev/v0.3.0` @ `353d418`（one-shot 任务组重构之后）。
**范围**：① 运行时开销（每任务提交/完成成本、常驻内存、队列热路径）；
② 公共 API 与设计（概念数量、误用陷阱、文档一致性、可收敛的公开成员）。

本文是这次调研的**入口**：方法、结论索引、独立验证轮、处置记录与未覆盖项。
细节在两份分报告里，本文不重复其内容。

| 文档 | 内容 |
|---|---|
| [perf-analysis-2026-09-28.md](perf-analysis-2026-09-28.md) | 运行时性能：实测数字、7 组问题、保留项与理由 |
| [api-design-analysis-2026-09-28.md](api-design-analysis-2026-09-28.md) | 公共 API 与设计：37 个公开类型盘点、P0–P7 问题、保留/降级清单 |
| [independent-verification-kimi-2026-09-28.md](independent-verification-kimi-2026-09-28.md) | 独立验证席位（`kimi-code/k3`）的原始判定与逐条证据 |

---

## 一、方法

**1. 公开表面的权威枚举。** 用 `javap -public` 对 `target/classes` 全量导出，得到
28 个顶层公开类型 + 9 个公开嵌套类型 = **37 个公开类型**，与
`src/test/.../PublicApiSurfaceTest.java` 钉住的清单一致；顶层类型共 **289 个公开成员**。
所有 API 结论以这份导出为准，不靠印象。

**2. 四路并行巡查 + 逐条复核。** 性能面与 API 面各铺开独立巡查（提交热路径、取消与上下文、
队列包、完成与观测；空值与失败形状、概念冗余、文档漂移、误用陷阱），
**每条结论回到源码复核**后才写入报告。证伪与降级的条目单列在报告末节，与结论同等重要。

**3. 实测优先于推断。** 性能结论用一次性 harness 取得（JDK 8u412 Corretto，12 核）：
分配量用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`（精确值），
时间用预热后 best-of-3，并反射读取 `ParRuntime` 内部定时器队列的真实长度。
API 面最关键的一条（`valuesOrThrow()` 的失败形状）写了复现程序实测。

**4. 独立验证轮。** 结论交由**另一个模型**（`kimi-code/k3`）复核：只读仓库、
自行写程序复现、被明确要求**主动证伪**且"没有证据的赞成票是废票"。
原始判定见 [独立验证文档](independent-verification-kimi-2026-09-28.md)。

> 关于席位可用性：`cmux` 的 CLI 有 socket 访问控制（默认 `socketControlMode: cmuxOnly`，
> 只允许从 cmux 内部启动的进程连接），本次会话的进程链是 `cc-connect ← claude ← zsh`，
> 不在 cmux 内，因此**未能经 cmux 派遣**，改用同一模型的 headless 调用
> （`kimi -m kimi-code/k3 -p`，显式指定模型以避免默认模型静默降级）。
> 席位身份是 `(harness, model)` 二元组，这一点与 `design/senate.md` 的约定一致。

---

## 二、结论索引

### 运行时性能（详见分报告）

| 优先级 | 问题 | 证据强度 |
|---|---|---|
| P0 | 已取消的 deadline 不从定时器队列移除：与提交次数 1:1 增长（10 万次提交留 10 万条、76 B/条），修复实测**残留归零且 `schedule+cancel` 快一倍**（310→161 ns） | 实测 |
| P1 | `Par.submit` 单价路径为单元素也建 3 个聚合 future + 1 次定时器：4578 B/op，是批量路径的 2.4× | 实测 |
| P2 | `TaskBatchResult` 无条件预建观测聚合（O(N) 监听器 + 一次多余的全量拷贝） | 读码 |
| P3 | 大 batch 常驻内存：任一已完成 task future 可达即钉住整批跟踪状态 | 读码 |
| P4/P5 | 队列包剩余项（`remove(Object)` 每次约 1 KB 垃圾 + 两趟遍历、节点分配仍在锁内等） | 读码 |
| P6 | 热路径常数项（无 deadline 也读时钟、每次提交的 `Optional`/字符串拼接等） | 读码 |

### 公共 API 与设计（详见分报告）

| 优先级 | 问题 | 证据强度 |
|---|---|---|
| P0 | `valuesOrThrow()` 在 fail-fast 场景抛 `CancellationException`(cause=null)，真实失败丢失；与用户指南 :93 的承诺及组路径已文档化的优先级规则冲突 | 实测 + 独立复核 5/5 |
| P1 | `CancellationToken` 是不可达公开类型：无任何公开成员返回/接受它，用户却能构造并 `cancel()` 空转；`State` 与 `TaskOutcome` 双词汇表 | 复核 |
| P2 | `TaskGraphObservationScope` 四个诊断断言在无作用域时静默返回 `false` | 复核 |
| P3 | 两个访问器会让用户静默出错（`TaskCompletion.failure()` 的 javadoc 口径；`failedTaskName()` 与 `members()` 不匹配） | 复核（其中一条经复核修正） |
| P4/P5 | 文档漂移与两份过期提案 | 复核后已修 |
| P6/P7 | 零调用公开成员、选项与形状次级项 | 读码 |

---

## 三、独立验证轮的处置

席位 `kimi-code/k3` 对 9 条主结论给出 **8 条 CONFIRMED、1 条 PARTIAL**，
并自证未修改仓库（`git status` 无 `M` 项）。三条需要记录的处置：

1. **P0 的成因被精确化。** 复核指出这不是竞态：token 的 fail-fast 监听在 `map` 内部
   `bind` 时就已注册，早于用户在 `valuesOrThrow()` 里新建的聚合监听，因此取消**稳定地**
   先被看到——这让"修复后行为可预期"成立。复现 5/5 轮一致。

2. **我的一条结论被修正（PARTIAL）。** API 报告 §4.1 初版称"超时/fail-fast 任务的
   `failure()` 一律为 null"并把填充路径归到 `Task.failure()`；复核指出填充在
   `TaskObservation.settledSnapshot`，且体抛中断形异常被归成 `TIMEOUT`/`FAIL_FAST` 时
   快照 failure **非 null**。我已按源码逐条核实并改写（含新增的"同一任务两个访问器
   可给出相反读数"这一更准确的发现），修正记录在该报告 §11。

3. **一条巡查结论被证伪并降级。** `SubmissionException` 包私有曾被报为"用户无法命名的
   失败类型漏洞"，核实后 `docs/en/migration-v0.2.md:266` 明确它是 internal type 并给出
   `getCause()` 用法——**有意设计**，降级为 javadoc 交叉引用瑕疵。

---

## 四、已执行的修复

| 变更 | 内容 |
|---|---|
| `1ba1937`（已推送） | 文档漂移修复：README 两条失效特性、`user-guide` 的"不可变"措辞与"显式 token 取消"、`migration-v0.2` 抬头指向从未发布的 API、`propagateCancellation` 的过度承诺、idea-graveyard/philosophy 的旧类型示例、`TaskBatchResult` javadoc 中已删除的 `FAILED`、`design/AGENTS.md` 三行悬空索引 |
| 未提交（工作区） | 两份提案的状态抬头与 §9 实施记录（`design/jspecify-null-safety-v0.3-proposal.md`、`design/scope-close-and-termination-proposal.md`）；已由复核逐行核对属实 |

---

## 五、复现条件与局限

- **不是 JMH 基准。** 数字用于量级与对比，不适合作为回归门禁。若需长期跟踪，应另立
  benchmark 模块（需改 `pom.xml`，按 `AGENTS.md` 的权限约定先征得同意；
  `jmh-core`/`jmh-generator-annprocess` 本地仓库已有）。
- **测量仪器在会话临时目录**（`/tmp/pisbench/`：`Bench.java`、`Probe.java`、
  `FailureShape.java`、`TimerProbe.java`）。**尚未纳入版本库**——需要长期保留的话应迁入
  仓库并在报告中改为仓库内路径。
- **只有单线程提交的测量**，多线程争用（`activeAdmissions` 的 RMW、全局 ID 序列的 CAS）
  未测；`TaskGroup` 路径未做性能实测。
- **未做二进制兼容性对比**（0.x 阶段不承诺）；未处理 javadoc doclint 警告。

---

## 六、下一步

两份分报告各自给出落地顺序。跨报告看，性价比最高的两件互不相干，可分别独立成 PR：

1. **性能 P0**（定时器策略，2 行，实测既治泄漏又快一倍）；
2. **API P0**（`valuesOrThrow()` 的失败优先级，有独立复现与组路径的现成规则可循；
   落在失败判定路径上，按仓库惯例应配独立的对抗性审查）。
