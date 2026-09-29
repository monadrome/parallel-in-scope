# 对抗性评审交接：冗余清理 16 commits（2026-09-29）

> 状态：**评审进行中**。本文是 review seat 的输入基线与 findings 记录。
> 被审对象：`dev/v0.3.0` 上 `353d418..8796036` 中由本轮冗余清理引入的 16 个 commit。
> 评审席位：cmux workspace 内的 Kimi（`kimi -p`，独立上下文，与写代码的 seat 不同模型）。
> 规则：seat **只读**，不改文件；findings 逐条由主 agent 复核后才处置。

## 1. 被审范围

```
eb9be6d fix: record graph edges in the scope a unit resolved to, not the ambient one
fb6b22a refactor: delete the unused ActionGate
1bbf690 feat!: remove the executor-tag subsystem
29da259 refactor: drop the BlockingRisk classification nothing reads
2d67e51 refactor: drop the purger's reset-generation dimension
9206349 feat!: remove two accessors with no callers
afa6fee refactor: read batch element outcomes from TaskFuture directly
fff2d35 refactor: converge the five saturatedNanos copies into Deadlines
fcb0edc refactor: converge the close budget and observation barrier into the tracker
f1f8641 refactor: converge the shared argument checks onto Guava preconditions
db27887 refactor: build both executor projections through one keying-parameterized pass
40c970b perf: hold one timeout scheduler instead of building one per bind
9c130a1 perf: precompute the group's member-and-combine list
d768cce refactor: drop the dead parameter on prepareGroupTask and settle on one clock
da3ddd4 style: drop public from package-private members and the last java.time FQN
549c542 docs: record the convergence work and drop the deleted classification
```

验证状态：`mvn clean verify` 绿，687 测试通过。**未跑** `mvn -Ppitest`（需用户授权）。

## 2. 攻击面（seat 必须按这几条找，不要泛泛而谈）

**A. 内核正确性（最高优先）**

1. `eb9be6d` 改了 observation scope 的解析与记边路径。`TaskGraphObservationScope.resolveFor(parent, owner)`
   现在是三个提交路径（`Par.submit`/`Par.map`/`TaskGroup.prepare`）唯一的归属判定。问：
   - 新的 `recordEdge` 实例方法检查 `closed()`，原静态 `logTaskPair` 经由 `data()` 也检查
     `closed()`——两者在"scope 正在关闭"的交错下行为是否真的等价？`close()` 里
     `closed.compareAndSet` 与 `publishReport()`/`restoreCurrentScope()` 的顺序，会不会让一条边
     记进已经 snapshot 过的 data？
   - `MultiTaskContext.Resolution` 仍保留"observation 未显式设置时从 structuralParent 继承"的
     fallback。三个调用点现在都显式传 `resolveFor` 的结果（可能是 null）。**null 与"未设置"
     在 Resolution 里是同一种状态吗？** 若是，一个 foreign scope 下的 unit 会不会经由 parent
     fallback 又拿回一个不属于本 runtime 的 scope？
   - `Par.map` 的 TTL 捕获点没有 install 已解析的 scope（`TaskGroup.prepare` 有）。batch 元素
     body 内 `current()` 返回的是提交线程当时的 scope。这是否让嵌套 batch 的归属判定再次走偏？

2. `fcb0edc` 把 close/await 的两段逻辑搬进 `BodyCompletionTracker`：
   - `awaitSettled` 新增了 `InterruptedException` 分支（原代码没有，会作为 undeclared checked
     exception 逃出）。新行为是"恢复中断标志 + 返回 false"。`TaskGroup.awaitBodyCompletion` 与
     `TaskBatchResult.awaitBodyCompletion` 都声明 `throws InterruptedException`——**在 body wait
     已经返回之后才被中断的调用者，现在拿到 false 而不是异常，这是否违反它们的 javadoc？**
   - `cannotFail` 参数为 null 时吞掉 `ExecutionException`/`CancellationException`。批次元素走
     null 分支。原代码在元素循环里也吞，但在聚合 `completionView` 上抛 AssertionError。搬移后
     两处语义是否与原来逐字一致？
   - `closeGraceBudgetNanos(configured, deadlineNanos)`：batch 侧原来判断
     `batchToken == null || deadlineNanos == MAX_VALUE`，现在由 `deadlineNanosOrNone()` 把
     null token 映射成 MAX_VALUE 再判断。**等价吗？**

3. `db27887` 改的是并发敏感的 snapshot 构建。`projectOntoExecutors` 泛型化后：
   - label keying 走 `edgesInTaskGraphOrder(taskGraph)`，identity keying 走记录顺序。
     **同一个 executor pair 内的边顺序，与改动前逐字相同吗？**（`TaskGraphProjectionOrderTest`
     声称 pin 住了，且改动前也绿——请自己验证这个测试是否真的能区分两种顺序，而不是恰好两种
     顺序下都通过。）
   - `Function<TaskEdge, @Nullable N>` 在 NullAway 下的注解位置是否真的表达了"返回值可空"？
     还是实际上表达了别的东西而恰好编译通过？

4. `9c130a1` 把 `membersAndTerminal` 变成构造期 `ImmutableList` 字段，并用它的 size 作为
   `totalTasks`。**收敛屏障依赖 `totalTasks` 与实际完成计数精确相等。** terminal 为 null 时字段
   直接复用 `orderedMembers`（同一对象，不是拷贝）——有无别名问题？`totalTasks` 与原
   `memberStates.size() + (terminal == null ? 0 : 1)` 在任何输入下都相等吗（注意 memberStates
   是 ImmutableMap，orderedMembers 是它的 values().asList()）？

**B. 契约 vs 实现**

5. `2d67e51` 删了 purger 的 generation 维度，并重写了 `ParRuntime.setPurgeEnabled` 的 javadoc
   为"disable 期间到达的信号立即 settle；开关翻转时已在外的估计按自己的 idle 边界过期"。
   **读代码验证这两句都成立**，特别是第二句：`recordIdleCancellation` 的过期判断用
   `previous.timestampNanos != 0L`，删掉 generation 后初始 marker 是 `(0L, 0L)`，首次信号的
   `previous.timestampNanos == 0` 会跳过过期——这与 javadoc 的承诺一致吗？
6. `f1f8641` 统一了异常文案，所有消息现在插值被拒值。**有没有哪条 public javadoc 的 `@throws`
   描述与新文案/新异常类型不再吻合？** 尤其 `GroupDraft` 从手写 if/throw 改成
   `checkArgument`/`checkState` 后，异常类型有无意外变化（IllegalArgument ↔ IllegalState）。
7. `9206349` 删了 `TaskGroupResult.memberCount()`（v0.2.0 就有，属破坏性）。迁移条目已加入
   两份 migration guide。**还有别的公开引用残留吗？**

**C. 测试质量（请务必反向验证）**

8. `TaskGraphScopeOwnershipTest`（新增 4 例）：我已反向验证 batch 例与 verdict 例会红。
   **另两例（submit、control）是否对着改动前的代码也会红？若不会，它们证明了什么？**
9. `ValidationTest`、`TaskGraphProjectionOrderTest`、`DeadlinesTest` 新增例：哪些是"改动后才成立"
   的行为，哪些只是复述实现？有没有一个恒真实现能让它们全绿？
10. `HeuristicPurgerExpiryTest.aCallbackDisabledAfterClaimingItsSequenceSettlesItsOwnEstimate`
    是被我重写过的（原例断言的是被删掉的 generation 语义）。**重写后它还在测一个真实的竞态，
    还是变成了测时序巧合？** 注意它依赖 `ControlledClock` 在第 1 次 `read()` 上阻塞。

**D. Java 8 与泛型**

11. `src/main/java` 只允许 Java 8 API。新代码里 `Deadlines.saturatedNanos`、
    `projectOntoExecutors`、`Validation.*`、`BodyCompletionTracker.awaitSettled` 有无越界？
12. `da3ddd4` 批量删了 49 个 `public` 修饰符。**有没有一个是实现了某个 public 接口/父类的方法
    而不带 `@Override`，因此降级后会在运行期或某个未编译路径炸掉？**（我只跳过了紧跟
    `@Override` 的行。）

## 3. 已知基线（seat 请视为已知，不要重复报告）

这些是我自己已经记录、已经处置或已经明确不做的，报告它们不算 finding：

- **双重 fold 未修**：带 combine 的成功组会折叠成员值两次（`assembleTerminal` 一次、
  `publishValues` 一次）。缓存需要字段，但 combine 在 `prepare` 的静态 body 里、group 尚未构造；
  改成 `GroupValues` 惰性折叠则要在跨线程发布的公开不可变类里加 volatile + 哨兵。判定：不值得，
  故意不做。
- **P2.5 保留两张 executor 图**：label keying 是 identity-less legacy 边的 fallback，删它要连
  legacy `TaskEdge` 构造器一起删，不在授权范围。只收敛了 build 逻辑。
- **P1.3 逆转了已落地决策**：`executor-transparency.md` §8 曾把死锁判定从 `BlockingRisk` 拆给
  `starvationProne()`，那次拆分没给 `BlockingRisk` 指定读者，所以它无人读取。已在该文档 §10 记录。
- **未跑 pitest**：需用户授权。
- **并行 agent**：另一个 seat 正在同一 worktree 做注释卫生清理（`ScopedCallable`、`Task`、
  `DrainingBlockingQueue` 等仍未提交）。它的改动不在本次评审范围；工作树里的未提交 diff 属于它。

## 4. Findings

> seat 报回后，主 agent 逐条复核并在此记录 verdict。severity 是 seat 的主张，不是事实。

### 第一轮（Kimi，`kimi-for-coding`，session_b297a7a5）

seat 报回 2 条 finding + 12 个 surface 判定 clean。逐条复核结果：

| # | surface | seat 主张 | 复核 verdict | 处置 |
|---|---|---|---|---|
| 1 | A2 | contract-violation：`awaitSettled` 吞 `InterruptedException`，与两个 public `awaitBodyCompletion` 的 `@throws` 及"false 意为预算耗尽"矛盾 | **成立，且我的 commit message 撒了谎** | 已修 `15a18e3` |
| 2 | A1 | consistency（low confidence）：静态 checker 读 ambient binding，记边走 resolved scope，混合拓扑下二者分叉 | **成立，且比 seat 说的更严重——是回归，不是分叉** | 已修 `a2b9ae6` |

**Finding 1 的复核**：seat 是对的，而且打中了我自己的错误陈述。我在 `0e9def5` 的 commit message 里
写"get() 的 `InterruptedException` 会作为 undeclared checked exception 逃出"——查
`0e9def5^` 的实际代码：两个 `awaitBodyCompletion` 都声明了 `throws InterruptedException`，元素
循环与屏障等待的 `get()` 都没有 interrupt catch，所以中断本来就正常传播、与 javadoc 一致。
我加的 catch 不是修漏洞，而是**引入**了契约违背：把"被中断"伪装成"预算耗尽"，调用方若按
"false = 再等等"处理，就会在一个已被要求停止的线程上继续轮询。

**Finding 2 的复核（升级）**：seat 标 low confidence、说构造不出错误判定，只是 checker 与记边
目标分叉。我用探针实测了同 runtime 的嵌套 scope：

| | body 内 `inner` scope | 外层 scope |
|---|---|---|
| P0 修复前 | `unit-1 -> unit-2`（batch 在此） | 只有 `root -> unit-1` |
| P0 修复后（缺陷态） | **空** | `root -> unit-1`, `unit-1 -> unit-2` |

用户显式为一段工作开的 scope 报告为空，边悄悄落到外层——这是 P0 修复引入的回归，不是"可辩护
的语义分叉"。根因：`resolveFor` 只看 structural parent 捕获时的 scope，从不看线程当前绑定，而
**嵌套 scope 是栈在线程上的**，最内层才是调用方的意思。

改成 ambient 优先后，`643ac7b` 堵的 laundering 洞立刻重开：TTL replay 让外来 runtime 的 worker
带着提交线程的 scope，ambient 优先就在一个本不该持有该 scope 的线程上找到了 owner 匹配。缺的是
传播边界那一半——`TaskGroup.prepare` 本来就在成员循环外 install 已解析 scope，`Par` 没有。两处
补齐后，嵌套记账与跨 runtime 拒绝同时成立，且去掉任一半都会红。

**未采纳/降级**：无。两条都成立。

**seat 的其他有效观察**（非 finding，已核）：

- handoff §1 的 SHA 与分支不符——属实，我 rebase 过（`56a6632` 之后），seat 按 subject 映射，正确。
- C8：新增测试的 submit 例与 control 例对着修复前的代码**不会红**，因为 `submitWhileOpen` 修复前
  就用的 `observation != null`，只有 `Par.map` 漏。属实；这两例是回归护栏而非缺陷证据，commit
  message 只声称 batch 例与 verdict 例会红，措辞准确。
- 两个 flaky 失败（`TaskBatchResultBodyCompletionTest`）：**未复现**。695/697 全绿多轮。seat 自己
  归因为机器负载，我的判断相同——并行 agent 与我共用同一个 `target/`，本轮我也撞到一次
  `cannot access queue.package-info` 的构建态碰撞。不是被审改动的问题。
- `join` 返回值被忽略（purger 测试）：属实，是 nit，不改。

### 第二轮（针对第一轮的修复）

按 AGENTS.md：第二轮只审第一轮改出来的新代码（`15a18e3`、`a2b9ae6`），不重打原设计。
基线 = 第一轮表 + §3。seat 报回 2 条：

| # | surface | seat 主张 | 复核 verdict | 处置 |
|---|---|---|---|---|
| 3 | R1 | doc-overclaim（confidence high）：`prepareUnderResolvedScope` 的 javadoc 说"这是本 unit 每个 task body 都会观察到的 scope"，但 restore 发生在 `submitScoped` 之前，所以 rejection 后 inline 跑在提交线程上的 body 看到的是 `previous` | **不成立，已驳回** | 不改 |
| 4 | R3 | test-gap（medium）：中断契约只在包私有 `awaitSettled` 层被 pin，公开 `awaitBodyCompletion` 的 phase 2/3 没有覆盖；把 catch 重新包在公开调用点上能全绿 | **成立** | 已补 `d2d1a7a` |

**Finding 3 驳回理由**：seat 的推理停在"restore 早于 submitScoped"，漏了一层——body 不是直接跑在
当前线程绑定上的，而是跑在 `TtlCallable` 回放的快照上，而那个快照正是在 `prepareUnderResolvedScope`
安装期间由 `TaskSubmissions.wrapScoped` 捕获的。实测（探针：foreign runtime + `runOnCallerThread(true)`
+ 被拒 executor，外层开着 owner 的 scope）：

```
PROBE inline body saw: null
```

inline body 看到的是**已解析的 scope（null）**，不是提交线程恢复后的 foreign scope。javadoc 的
表述准确。这条是 AGENTS.md"reported severity is a claim, not a fact"的现成例子：seat 对机制
标了 high confidence，仍然错。

**Finding 4 的价值**：这是本轮最有用的一条，且是我自己两轮反向验证都不会发现的类型——我只反向
验证"我改过的那一行"（`awaitSettled` 的 catch），而它问的是"把同样的 catch 挪到上一层调用点，
测试还咬得住吗"。答案是咬不住。补测后，用 seat 点名的那个 mutation 实测会红。

### 结论

两轮共 4 条 finding：3 条成立并已修（2 个真实缺陷 + 1 个测试缺口），1 条驳回。
两个真实缺陷都出自我自己这批"冗余清理"改动，且都不是清理本身——是清理时顺手改的行为：
一个把中断伪装成预算耗尽，一个把嵌套 scope 的记账搬去了外层。

验证：`mvn clean verify` 绿，697 测试。**仍未跑** `mvn -Ppitest`（需用户授权）。
