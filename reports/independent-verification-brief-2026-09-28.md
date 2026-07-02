# 独立验证任务：parallel-in-scope 公共 API 分析结论复核

你是一个**独立验证席位**。另一个模型在仓库 `dev/v0.3.0` 分支上做了一轮公共 API 与设计分析，
得出了下面 9 条结论。你的任务是**独立复核每一条**，判定其成立、不成立、还是部分成立。

## 硬性约束

1. **只读仓库**：不得修改本仓库（`parallel-in-scope`）下的任何文件。
2. 需要写验证程序时，只能写在 `/tmp` 下（例如 `/tmp/pis-verify/`），编译运行随你。
   仓库已经编译过，`target/classes` 可用于类路径；依赖 jar 在 `~/.m2/repository`
   （guava 33.6.0-jre、transmittable-thread-local 2.14.5、jspecify 1.0.1，另需
   `com.google.guava:failureaccess`）。本机 JDK 8 在
   `~/Library/Java/JavaVirtualMachines/corretto-1.8.0_412/Contents/Home`。
3. **你的价值在于证伪**。逐条主动尝试推翻它；如果某条其实是误读、过度断言或措辞不准确，
   明确说出来，并给出正确的表述。不要因为"听起来合理"就投赞成票——**没有证据的赞成票是废票**。
4. 每条结论都必须给出**证据**：文件:行号，或你自己跑出来的命令与输出。不要只给判断。

## 待验证的结论

**A1（最重要，声称有运行期复现）**
`TaskBatchResult.valuesOrThrow()` 在"批次中途某元素失败、fail-fast 取消了更早位置的兄弟元素"时，
会抛出 `java.util.concurrent.CancellationException`（且 `cause` 为 null），**而不是**
文档承诺的、携带真实失败的 `ExecutionException`。
- 声称的依据：`docs/en/user-guide.md:93` 承诺 "propagates the first failure … as an `ExecutionException`"；
  而 `docs/en/user-guide.md:179-183` 给**组**路径规定了优先级（有失败记录→ExecutionException；
  无失败记录的取消→CancellationException），批量路径没有遵守这条规则。
- 请自行写程序复现（4 线程池、若干元素 sleep、其中一个抛异常），报告你实际观察到的异常类型与 cause。
- 并判断：javadoc（`TaskBatchResult.java:154-167`）与用户指南在这一点的说法是否一致。

**A2**
`io.github.monadrome.parallelinscope.CancellationToken` 是公开类型且可公开构造
（`new CancellationToken()` / `create()` / `cancel()`），但**整个公开 API 中没有任何成员
返回或接受它**——因此用户能构造并 `cancel()` 一个不连接任何工作的 token，编译通过、运行无副作用。
- 请用你自己的方式枚举公开表面（例如写程序扫描 `target/classes`，或读源码）来确认或推翻
  "无公开成员接受/返回它"这一断言。
- 并判断 `design/extension-and-wrapping.md` 是否把它规定为有意的扩展点。

**A3**
`TaskGraphObservationScope` 的四个公开静态方法 `hasTaskCycle()` / `hasSelfLoop()` /
`hasExecutorCycle()` / `hasExecutorSelfLoop()` 读取线程本地的当前作用域；
**在没有活动作用域时它们返回 `false`**，因此"检查过没有环"与"根本没有作用域"不可区分。
- 依据声称：`TaskGraphObservationScope.java:44-45` 的静态 TTL、`:62-63` 的安装、`:110-145` 的四个断言。
- 请确认这四个方法各自在"无作用域"时的返回值，并判断这是否是静默错答。

**A4**
`TaskCompletion.failure()` 的 javadoc 写 "Returns the task failure, or null on success."
（`TaskCompletion.java:218`），但实现上**超时 / fail-fast / 被取消的任务 `failure()` 也是 null**
（依据声称：`Task.java:259-263` 只在 `USER_FAILURE` / `SUBMISSION_FAILURE` 时返回异常）。
- 请确认。并判断 `TaskFuture.failure()` 的 javadoc 是否更准确（两者是否不一致）。

**A5**
`TaskGroupResult.failedTaskName()` 的 javadoc 说它可能是"第一个失败的成员**或 terminal combine**"，
而 `TaskGroupResult.members()` 只包含成员、**不包含 combine**；因此用户按字面写
`result.members().get(result.failedTaskName())` 在 combine 失败时会 NPE。
- 依据声称：私有方法 `failedTaskSnapshot()`（`TaskGroupResult.java:160-166`）实现了正确的 fallback
  但没有公开；`orThrow()` 用的是它（`:114`）。
- 请确认或推翻。

**A6**
`README.md:87` 的 "CPU / IO task-aware scheduling" 与 `:88` 的
"Monitoring SPI for execution, queueing, and failures" 都是**失效描述**：
前者与 `TaskType.java:4-13` 的自述矛盾（只驱动 `SmartBlockingQueue` 的入队决策），
后者的 SPI（`TaskListener`）已被删除。

**A7**
`docs/en/migration-v0.2.md:3-5` 告诉 0.2.x 用户新 API 是 `defineGroup*` / `submitGroup` / `Bindings`，
但这三个名字**从未作为公开 API 存在**（当前是 `runtime.group(name,timeout).par(...).submitAll()`）。

**A8**
`docs/en/user-guide.md:33` 的 "`ParRuntime` is immutable after `build()`" 与两个公开 setter
（`ParRuntime.java:258,270`）矛盾；setter 本身有 javadoc 说明是有意的运行时调整，过强的是指南那句。

**A9（验证的是别人的一次"更新"，请特别严格）**
`design/jspecify-null-safety-v0.3-proposal.md` 与 `design/scope-close-and-termination-proposal.md`
刚刚被标注为"已实施"并各自新增了 §9 实施记录。请**核对这两份文档里新写的内容是否属实**：
- jspecify 那份 §9 声称的实际值：`pom.xml:56-57` 是 errorprone 2.50.0 / nullaway 0.14.2；
  `pom.xml:135` 落地即 `-Xep:NullAway:ERROR`；`pom.xml:153,165-168` 有 enforcer `[21,)`；
  两个 `package-info.java` 已 `@NullMarked`；破坏性变更记录在 `docs/en/migration-v0.3.md:679`；
  另有一条方案未提的 `-XepOpt:NullAway:HandleTestAssertionLibraries=true`。
- scope-close 那份 §9 声称：`TaskBatchResult` 现在 `implements AutoCloseable` 且有 `close()`
  （`TaskBatchResult.java:37,237`）；`BodyCompletionTracker.stuckBodySummary()` 在 `:123`、
  其 WARN 在 `:227-228`；自等待守卫的 `IllegalStateException` 契约在 `TaskGroup.java:331,395`。
**逐条核对行号与事实**。任何一处写错、写偏或把"计划"写成"实际"的，都要指出。

## 输出

把结论写入 `/tmp/pis-verify/verdict-kimi.md`，格式：

```
## 结论

| 编号 | 判定 | 一句话理由 |
|---|---|---|
| A1 | CONFIRMED / REFUTED / PARTIAL | … |

## 逐条证据

### A1
- 我做了什么（读了什么 / 跑了什么命令）
- 我观察到什么（原始输出片段）
- 判定与理由
- 如果你认为原结论的表述有偏差，写出你建议的正确表述
```

判定用词只用这三个：`CONFIRMED`（成立）、`REFUTED`（不成立）、`PARTIAL`（部分成立/表述有偏差）。
另外单独一节 `## 额外发现`，写你在验证过程中发现的、上面 9 条之外的问题（没有就写"无"）。
