# 独立验证结论：parallel-in-scope 公共 API 分析复核（dev/v0.3.0 @ 353d418）

验证人：Kimi Code（独立席位）。验证期间仓库只读，未修改任何仓库文件；复现程序与本文均在 `/tmp/pis-verify/` 下。

## 结论

| 编号 | 判定 | 一句话理由 |
|---|---|---|
| A1 | CONFIRMED | 复现程序 5/5 次抛出 `CancellationException`（cause=null），真实失败 `boom-3` 仅被 Guava 以 SEVERE 记日志；用户指南 :93 的承诺在 fail-fast 主路径上不成立 |
| A2 | CONFIRMED | 对 `target/classes` 全部类（含嵌套类）做 javap 扫描：除 `CancellationToken` 自身外，没有任何 public 类的 public 成员返回/接受它；`extension-and-wrapping.md` 未将其列为扩展点 |
| A3 | CONFIRMED | 四个方法均为 `data != null && data.xxx()`，无作用域时返回 `false`（代码 + 运行实测），与"检查过无环"不可区分 |
| A4 | PARTIAL | javadoc 不准确与 `TaskFuture.failure()` 更精确这两点成立；但机制归因错误——`TaskCompletion.failure` 的填充路径在 `TaskObservation.settledSnapshot`，且"超时/fail-fast 任务的 failure() 必为 null"并非恒真 |
| A5 | CONFIRMED | combine 失败时 `failedTaskName()` 是 combine 名，而 `members()` 不含 combine，`members().get(failedTaskName())` 返回 null（解引用即 NPE）；正确的 fallback 是私有方法 |
| A6 | CONFIRMED | README.md:87-88 两条描述均失效：`TaskType` 自述"不是调度指令"，`TaskListener` SPI 已在 0.3 删除 |
| A7 | CONFIRMED | migration-v0.2.md:3-5 提到的 `defineGroup*`/`submitGroup`/`Bindings` 在当前 API 中不存在；它们仅在 dev 分支历史中短暂存在过（被 0ff3f4c 替换），从未进入任何已发布版本 |
| A8 | CONFIRMED | user-guide.md:33 的 "immutable after build()" 与 `ParRuntime.java:258,270` 两个有意的运行时 setter 直接矛盾，过强的是指南那句 |
| A9 | CONFIRMED | 两份文档 §9 的所有行号与事实逐条核对无误（仅 BodyCompletionTracker 的 WARN 调用实际起于 :226，:227-228 是消息体行，属可接受的指代） |

## 逐条证据

### A1 — CONFIRMED

- 做法：在 `/tmp/pis-verify/A1Repro.java` 写复现程序（4 线程池、6 个元素、parallelism 默认、元素 3 睡 100ms 后抛 `IllegalStateException("boom-3")`，其余元素睡 5s），类路径用 `target/classes` + guava 33.6.0-jre + failureaccess 1.0.3 + ttl 2.14.5 + jspecify 1.0.1，连跑 5 轮。
- 观察（每轮一致）：

  ```
  run 1: thrown=java.util.concurrent.CancellationException message=Task was cancelled. cause=null
  SEVERE: Got more than one input Future failure. Logging failures after the first
  io.github.monadrome.parallelinscope.LeanCancellationException: cancel during running
  SEVERE: ... java.lang.IllegalStateException: boom-3 ...
  run 1 element outcome=FAIL_FAST failure=null        (×3，位置 0-2)
  run 1 element outcome=USER_FAILURE failure=java.lang.IllegalStateException: boom-3   (位置 3)
  run 1 element outcome=FAIL_FAST failure=null        (×2，位置 4-5)
  ```

  5/5 轮 `valuesOrThrow()` 抛的都是 `java.util.concurrent.CancellationException`（message="Task was cancelled."，cause=null）。真实失败 `boom-3` 只出现在 Guava `AggregateFuture` 的 SEVERE 日志里，不作为异常传给调用者。
- 机理：`valuesOrThrow()` 是 `Futures.allAsList(results).get()`（`TaskBatchResult.java:170`）。token 的 fail-fast 监听在 `map` 内 `bind` 时注册（`CancellationToken.java:168-184`），早于用户在 `valuesOrThrow()` 里新建的 allAsList 的监听；元素 3 失败 → 先级联取消兄弟元素 → allAsList 先看到被 cancel 的输入而整体 cancel → `get()` 抛 `CancellationException`；后到的 `setException(boom-3)` 失败并被 Guava 记为 SEVERE。这不是竞态，注册顺序决定了结果稳定。
- 文档对照：
  - `docs/en/user-guide.md:93`："propagates the first failure — including a submission failure — as an `ExecutionException`" —— 在 fail-fast 这个主路径上**不成立**，且全文未提批量路径会抛 `CancellationException`。
  - 组路径 `valuesFuture()` 的优先级表（user-guide.md:179-183）：有失败记录 → ExecutionException，无失败记录的取消 → CancellationException。批量路径没有等价规则——同一语义事件（兄弟失败触发 fail-fast）在组路径拿到带真实 cause 的 ExecutionException，在批量路径拿到 cause=null 的 CancellationException。A1 的这一对比成立。
  - javadoc（`TaskBatchResult.java:154-167`）与用户指南**不完全一致**：javadoc 的 `@throws` 列表同时列了 `ExecutionException` 和 `CancellationException`（:164-165），但正文 "the first element failure propagates"（:158-159）仍然过度承诺——实测中 first failure 并不传播。指南 (:93) 则连 `CancellationException` 的可能性都没提。所以准确说法是：javadoc 的 throws 列表碰巧覆盖了真实行为，但两处文档的正文都误导。
- 建议的正确表述：`valuesOrThrow()` 在"元素失败触发 fail-fast 取消兄弟元素"时抛出 cause=null 的 `CancellationException`，真实失败只能通过 `results()`/`report()`/`completionFuture()` 取回；用户指南 :93 的 ExecutionException 承诺在该场景不成立，且与组路径 `valuesFuture()` 的失败优先规则不一致。

### A2 — CONFIRMED

- 做法：对 `target/classes/io/github/monadrome/parallelinscope/**/*.class`（含全部嵌套类）逐个 `javap`，grep `CancellationToken`；再回源码核对涉及类的可访问性。
- 观察：非私有成员中引用 `CancellationToken` 的类只有：
  - `CancellationToken` 自身（public 类，public 构造器 ×3、`create()`、`cancel()`/`cancel(boolean)`、`state()`、`originState()` 等）；
  - `MultiTaskContext.cancellationToken()`（方法 public，但 `MultiTaskContext` 是包私有类，源码 :19 `final class MultiTaskContext`，无 `public` 修饰）；
  - `TokenOutcomes.forCanceled(...)`（public static，但 `TokenOutcomes` 包私有）；
  - `Task`（包私有类，`final class Task<T>`）、`TaskObservation`（包私有）的包私有静态方法；
  - `TaskBatchResult.of(...)` 重载（javap 显示无 `public` 修饰，包私有工厂）。
- 结论：没有任何 **public 类的 public 成员** 返回或接受 `CancellationToken`（除其自身）。用户可以 `new CancellationToken()` 并 `cancel()`，编译通过；对库的运行零作用——`bind()`（唯一把 token 接到任务上的方法）是包私有的（`CancellationToken.java:141`）。
- `design/extension-and-wrapping.md` 判断：该文通篇未提及 `CancellationToken`（grep 无命中），其既定结论是"唯一的用户扩展点是任务体本身……不提供装饰器 SPI"。所以该文**没有**把它规定为有意的扩展点；它的 public 性没有设计文档背书。
- 表述上的两点精确化（不改变判定）：(a) 用户自己构造的 token 之间仍有父子传播（`new CancellationToken(parent)` 会挂到 parent 的 futureToken 上），所以"运行无副作用"仅相对库的工作成立；(b) `docs/en/user-guide.md:412` 把 "explicit `CancellationToken` cancellation" 列为面向用户的场景，但公开 API 里没有任何地方能把库内部的 token 交给用户——这条指南文字本身就依赖一个拿不到的对象（见"额外发现"）。

### A3 — CONFIRMED

- 做法：读 `TaskGraphObservationScope.java`，并在 A1 复现程序主线程（无任何作用域）直接调用四个方法实测。
- 观察：
  - 静态 TTL 在 :44-45；构造器中安装在 :62-63（`previousScope = CURRENT.get(); CURRENT.set(this);`）；四个方法在 :110-145，每个都是 `TaskGraphData data = data(); return data != null && data.xxx();`（:111-112、:121-122、:132-133、:143-144），`data()` 在无作用域时返回 null（:77-80）。引用的行号全部准确。
  - 运行实测输出：`[A3] no-scope hasTaskCycle=false hasSelfLoop=false hasExecutorCycle=false hasExecutorSelfLoop=false`。
- 判定：无作用域时四个方法都静默返回 `false`，与"在有作用域且检查过、确实无环"完全不可区分——方法签名和 javadoc（"the current request graph"）都没有暴露"无作用域"这个第三态，也不像 `Checkpoints.checkpoint(taskName, ...)` 那样在无作用域时抛 `IllegalStateException`。事实层面成立；"是否算静默错答"是设计判断，但作为 public static 查询，调用方无法防御，倾向于认可这是一个真实缺陷。

### A4 — PARTIAL

- 成立的部分：
  - `TaskCompletion.java:218` javadoc 确为 "Returns the task failure, or null on success."，而行号、引文均准确。
  - 被取消（future 层面 cancel）的任务——超时、fail-fast、组取消的典型路径——快照 `failure` 为 null（`TaskObservation.settledSnapshot` :210-221 的 `isCancelled()` 分支传 null）。A1 复现中 FAIL_FAST 元素 `failure=null` 是运行实证。这些任务 outcome 不是 SUCCESS 却 `failure()==null`，javadoc 的 "null on success" 确实不准确。
  - `TaskFuture.failure()` 的 javadoc（`TaskFuture.java:74` "Returns the failure behind a USER_FAILURE or SUBMISSION_FAILURE; null otherwise."）准确，与 `Task.java:259-263` 的实现一致。两者 javadoc 精度不一致这一点成立。
- 不成立/有偏差的部分：
  - **机制归因错误**：`TaskCompletion.failure` 的填充不走 `Task.failure()`（:259-263 是 `TaskFuture` 视图方法的实现），而走 `TaskObservation.settledSnapshot`（`TaskObservation.java:201-248`）。在 `ExecutionException` 分支（:235-246），无论 `classifyFailure` 把 outcome 归成什么，`cause` 都原样写入快照。
  - 因此"超时/fail-fast 任务的 `failure()` 也是 null"**并非恒真**：任务体抛出 `InterruptedException`/`CancellationException` 并被 `TokenOutcomes.causedByCancellation`（`TokenOutcomes.java:65-67`）归因成 `TIMEOUT`/`FAIL_FAST` 时，`TaskCompletion.failure()` 是**非 null** 的（就是那个中断异常）。只有 future 被整体 cancel 的路径才是 null。
  - 附带后果：同一任务上 `TaskFuture.failure()`（null，因为 outcome 非 USER/SUBMISSION_FAILURE）与 `TaskCompletion.failure()`（非 null）可以不一致——比原结论更复杂。
- 建议的正确表述：`TaskCompletion.failure()` 的 javadoc "or null on success" 不准确——future 被取消的任务（典型的 TIMEOUT/FAIL_FAST）failure 为 null；但反方向也不成立：体抛中断形异常被归类为取消的任务，快照 failure 非 null 而同任务的 `TaskFuture.failure()` 为 null。填充逻辑在 `TaskObservation.settledSnapshot`，与 `Task.java:259-263` 无关。

### A5 — CONFIRMED

- 做法：读 `TaskGroupResult.java` 全文与 `TaskGroup.java` 的 failedTaskName 写入/快照构造。
- 观察：
  - `failedTaskName()` javadoc（`TaskGroupResult.java:71`）："the first failed member **or terminal combine**"；`members()` javadoc（:76-77）只含成员。
  - 写入侧：`TaskGroup.java:843-845` 对 USER_FAILURE/SUBMISSION_FAILURE 做 `failedTaskName.compareAndSet(null, member.name)`，循环覆盖 terminal combine（:840 有 `member != terminal` 的区分）；快照构造（:1073-1084）中 `members` map 只来自 `orderedMembers`，combine 单独进 `terminal` 字段（:1083）。
  - fallback：私有 `failedTaskSnapshot()`（`TaskGroupResult.java:160-166`）正是 `members.get(failedTaskName)` 落空后退回 `terminal`；`orThrow()`（:114）与 `recordedFailure()`（:172-176）都用它。引用行号全部准确。
- 判定：combine 失败时 `result.members().get(result.failedTaskName())` 得到 null，用户解引用即 NPE。严格的措辞修正：`members().get(...)` 这个表达式本身返回 null，NPE 发生在后续解引用——但结论实质成立：公开 API 没有提供按 `failedTaskName()` 取回快照的正确途径，正确逻辑藏在私有方法里。

### A6 — CONFIRMED

- `README.md:87` = "- CPU / IO task-aware scheduling"，`:88` = "- Monitoring SPI for execution, queueing, and failures"（sed 实测行号精确）。
- `TaskType.java:4-13` 自述："Currently drives only the enqueue decision of `SmartBlockingQueue`"，且 :26 明说 "it is not a **scheduling** instruction"——与 README 的 "scheduling" 直接矛盾。
- `TaskListener`：`src/main` 全树 grep 无此类型；`docs/en/migration-v0.3.md:39` 与 `:117-120` 明确记录其在 0.3 被删除（观测改由 `completionFuture()` 承载）。"Monitoring SPI" 作为 SPI 已不存在。
- 判定：两条都是失效描述。细微处：观测能力本身还在（completionFuture 快照），消失的是 "SPI"（推式监听注册面）；README 用的是 "Monitoring SPI"，作为能力清单条目仍然误导。

### A7 — CONFIRMED

- `docs/en/migration-v0.2.md:3-6` 的 v0.3 注记原文：`"...were removed or redesigned around defineGroup* / submitGroup / Bindings."`（注记跨 3-6 行，brief 写 3-5，无碍）。
- 当前 `src/main/java` 无 `defineGroup`/`submitGroup`（grep 零命中）；`Bindings` 仅命中包私有内部类 `TaskGroup.RunBindings` 与包私有方法 `submitPreparedGroup`，均非公开 API。当前组 API 确为 `runtime.group(name, timeout).par(...).submitAll()`。
- 历史核查（修正原结论的措辞）：`git log --all -S defineGroup` 命中 `0ff3f4c "feat!: replace the three-stage task-group API with a one-shot chain"` 及若干 WIP index 提交——即这三个名字**曾在 dev/v0.3.0 分支历史中作为快照 API 短暂存在**，在 0ff3f4c 被一次性替换。由于 0.3.0 尚未发布（仍是 0.3.0-SNAPSHOT，user-guide.md:3），它们确实**从未出现在任何已发布版本**中。对 0.2.x（已发布线）用户而言，该注记指向了当前代码里不存在的名字，注记已过期——核心判定不变。
- 建议表述：`defineGroup*`/`submitGroup`/`Bindings` 从未进入已发布版本，且在当前 0.3.0-SNAPSHOT 源码中已被 one-shot 链取代；migration-v0.2.md 的 v0.3 注记是过期描述。

### A8 — CONFIRMED

- `docs/en/user-guide.md:33`："Ids are registered at build time. `ParRuntime` is immutable after `build()`..."。
- `ParRuntime.java:258` `public void adjustPurgeThresholds(double, double)`（javadoc："Adjusts both advisory purge thresholds for subsequent purge evaluations"）与 `:270` `public void setPurgeEnabled(boolean)`（javadoc："Enables or disables automatic purge at runtime"）——行号与 brief 一致，两个都是 public 运行时 setter。`purgePolicy()` 的 javadoc（:226-231）也明说"Runtime adjustments made through ... are not reflected here"，即运行时调整是有意设计。
- 判定：指南的 "immutable after build()" 过强，与两个公开 setter 直接矛盾；setter 有 javadoc 背书属有意。可辩护的读法是该句想表达"拓扑（id→executor 注册）不可变"（同句前半截的语境），但按字面它就是错的。

### A9 — CONFIRMED（逐条核对，两份文档 §9 均属实）

jspecify-null-safety-v0.3-proposal.md §9（:194-210）：

| §9 声称 | 核对结果 |
|---|---|
| `pom.xml:56-57` errorprone 2.50.0 / nullaway 0.14.2 | ✔ 实测 :56 `<errorprone.version>2.50.0</...>`、:57 `<nullaway.version>0.14.2</...>` |
| `pom.xml:135` 落地即 `-Xep:NullAway:ERROR` | ✔ :135 为 `-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:HandleTestAssertionLibraries=true` |
| `pom.xml:153,165-168` enforcer `[21,)` | ✔ :153 `maven-enforcer-plugin` artifactId、:165-168 `requireJavaVersion`/`[21,)`/message/闭合 |
| 两个 `package-info.java` 已 `@NullMarked`（:9、:13） | ✔ grep -n 实测 root `package-info.java:9:@NullMarked`、`queue/package-info.java:13:@NullMarked` |
| 破坏性变更在 `docs/en/migration-v0.3.md:679` | ✔ :679 恰为 `## Nullability annotations are now JSpecify` 标题行 |
| 方案未提的 `HandleTestAssertionLibraries=true` | ✔ 确实只在 pom :135 出现，§3.2 计划中无此项 |

scope-close-and-termination-proposal.md §9（:158-181）：

| §9 声称 | 核对结果 |
|---|---|
| `TaskBatchResult implements AutoCloseable` 且有 `close()`（:37,:237） | ✔ :37 `public final class TaskBatchResult<T> implements AutoCloseable`、:236-237 `@Override public void close()` |
| `BodyCompletionTracker.stuckBodySummary()` 在 :123 | ✔ :123 恰为方法签名行 |
| WARN 在 :227-228 | 基本准确：`logger.warning(...)` 调用起于 :226，:227-228 是消息体所在行（含 `stuckBodySummary()` 拼接），指代无误但起点行号窄了一行 |
| 自等待守卫 `IllegalStateException` 契约在 `TaskGroup.java:331,395` | ✔ :331 是 `close()` 的 `@throws IllegalStateException ... including a nested inline call`、:395 是 `awaitBodyCompletion` 的同款 `@throws` |

- 另抽查 §9 的"反转/偏离"自述：`TaskBatchResult` 的 `close()` 存在且语义与描述一致（`TaskBatchResult.java:236-251`）；`TaskListener` 确已删除（见 A6）；组入口确为 one-shot 链。未发现把"计划"写成"实际"的条目。

## 额外发现

1. **user-guide.md:412 引用了用户拿不到的对象**："a timeout, explicit `CancellationToken` cancellation, or cancellation of a parent batch has the same cooperative boundary" —— 但公开 API 没有任何成员能把库内部的 `CancellationToken` 交给用户（A2 已证），用户构造的 token 又不连接任何库工作。这条指南文字描述的"显式 token 取消"对用户实际不可执行，与 A2 互为印证，且是指南自身的失效描述。
2. **`TaskFuture.failure()` 与 `TaskCompletion.failure()` 可对同一任务给出不同答案**：任务体抛 `InterruptedException`/`CancellationException` 并被归因成 `TIMEOUT`/`FAIL_FAST` 时，快照携带该异常（`TaskObservation.java:235-246` 无条件写入 cause），而 `TaskFuture.failure()` 按 outcome 返回 null（`Task.java:259-265`）。两份 javadoc 各自大体准确，但 API 使用者会观察到同一任务的两种"失败"读数。（此发现由 A4 的机制核查引出。）
3. **A1 场景下真实失败只进日志**：`valuesOrThrow()` 路径上，触发 fail-fast 的那个真实失败被 Guava `AggregateFuture` 降级为 SEVERE 日志（"Got more than one input Future failure"），调用方拿到的 `CancellationException` 不带任何线索（cause=null、message="Task was cancelled."）。排障依赖日志，这对一个以"不忘记失败"为卖点（javadoc :158 "forgetting to handle failure is not possible here"）的 API 是实质矛盾。
