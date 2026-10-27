# 仅测试可达内核面退役提案

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未实现。`TaskGroup` 的 `futureOf`/`valuesFuture`/`completionFuture`/`awaitBodyCompletion`、`TaskBatch` 的 `completionFuture`/`awaitBodyCompletion`，以及 `TaskFuture`、`TaskGroupReport` 仍在。

> 状态：**设计提案，待拍板**。本文处理最终清单 #10：退役包私有内核上只有测试在用的句柄式访问面。
> 拍板必须**明确推翻** `synchronous-scope-exit.md:90-92` 的保留条款。本文附删后编译闸门的实测结果、
> 测试迁移分类，以及与 #11 的先后关系。基线：dev/v0.3.0 HEAD dd12641。

## 0. 结论

**推荐：推翻保留条款，按 §2 的删除集退役。** 删后编译闸门已经跑过：以 opus Q8 安全删除集 + N4 为底稿，
主源码只有 3 处被迫改动，另有 1 个成员建议保留（`ExecutionPhaseHintFuture.callableReleased()`）。清理传递性
死代码和 9 处悬空 `{@link}` 之后，`javac --release 8 -Xdoclint:reference/private` 通过；公开面与 dd12641 完全
一致（`javap -public` 全量比对，以及仓库 `check-api-surface.py` 的 diff 函数：removed=0、added=0）。主源码
**删 987 行、加 20 行**，根包 11,183 → 10,216 行（约 -8.6%）。

测试侧：77 个测试文件中有 28 个引用了被删成员，按编译错误归到 262 个测试方法。151 个迁到公开的
`map`/`runAll`；14 个通过「测试自己注册的 executor」重建外部取消；48 个留在内核层直测，只换句柄来源；
49 个随被删 API 一起删除（约 1,580 行）。**不需要新增任何窄测试钩子**：用户注册的 executor 收到的
`Runnable` 就是任务的引擎 future，这是公开面上就有的通道，已经实测（§4.3）。

先后关系：#11 中不碰删除集的部分先做，然后做本条，最后收 #11 的三项尾巴（`TaskGroupReport` 删除、
`TaskFuture` 接口删除、`Resolution` 收敛）。这三项在不触碰删除集的前提下做不完（§7）。

## 1. 被推翻的条款与理由

`synchronous-scope-exit.md:90-92` 写的是：「内部保留旧运行对象供取消、调度、观测屏障及既有并发测试」。
这句话混合了两层东西：

- **运行对象本身**（`TaskGroup`、`TaskBatch`、`MemberState`、各 future）承载取消、调度与观测屏障，必须保留，
  本文不动它们。
- **它们的句柄式访问面**（`futureOf`/`valuesFuture`/`completionFuture`/`close`/`awaitBodyCompletion`/`report`…）
  以及只为这些句柄存在的字段和防御代码。主源码零调用，退役的就是这一层。

推翻的理由：

1. **测试验证的是用户走不到的路径。** 例如 `TaskGroupValuesFutureTest` 断言 `valuesFuture()` 的取消语义，
   用户只能看到 `TaskGroupResult.valuesResult()`。两条路径各自正确，证明不了用户路径正确。
2. **生产路径为测试付费（N4）。** 每次组运行都分配 `completionTask`（含一个 `TaskObservation` 和挂在
   `completion` 上的监听节点；收敛时再算一次 `groupSummary` 快照）、`valuesView` 包装、`members` 的
   `LinkedHashMap` + `ImmutableMap` 拷贝；每个 `BodyCompletionTracker` 分配一个 identity `HashSet`，并在每次
   `register` 时维护它，只为自等待守卫服务。这些都是常数项，没有做基准；价值在于生产路径不再为测试付费，
   不在性能。
3. **挡住后面的收敛。** 删后 `TaskFuture` 在主源码中的非 javadoc 引用从 31 降到 12，`TaskGroupReport` 从 12 降到 7，
   `MultiTaskContext.resolution(...)` 调用点从 6 降到 5（§7）。
4. **文档在为不可达代码背书。** `Par.submit` 的 javadoc 仍自称「the unary entry point」，而 0.3 已把单任务迁到
   单成员组（`CHANGELOG.md:28`、`docs/en/migration-v0.3.md:55`）；契约里的 `close()`/自等待 MUST 条款约束的是
   用户触达不到的句柄（`overdesign-analysis-with-docs-2026-10-02.md` §11 D2）。

## 2. 删除集与闸门结果

### 2.1 底稿（opus Q8 + N4 原样列出；与它不一致的实测见 §2.2）

| 类 | 删除 |
|---|---|
| `Par` | `submit`、`submitWhileOpen` |
| `TaskBatch` | `results()`、`completionFuture()`、`valuesOrThrow()`、`firstRecordedFailure()`、`report()`、`reportString()`、`close()`、`awaitBodyCompletion()`、嵌套 `BatchReport` |
| `TaskGroup` | `valuesFuture()`、`completionFuture()`、`findMember`、`members()`、`futureAt`×2、`futureOf`×2、`terminalFuture`、`cancel`、`close`、`awaitBodyCompletion`、`callableReleasedAt`、`callableReleased(String)`；字段 `completionTask`、`valuesView`、`members` |
| `BodyCompletionTracker` | `checkNotSelfAwait`、`awaitBodyCompletion`、`awaitBounded`、`cancelAndAwaitBodyExit`、`awaitSettled` |
| `ExecutionPhaseHintFuture` | `callableReleased()`（**本文建议保留**，§5） |
| `TaskGroupReport` | `members()`、`terminal()`、`orThrow()`、`outcomeCounts()`、`reportString()` |

保留边界与 Q8 一致：`TaskBatch.submitCanceller()`/`finish`/`finishCombined`/两个 `of`；
`TaskGroup.prepare`/`start`/`submitPrepared`/`finish`；`BodyCompletionTracker.create`/`empty`/`register`/`release`/
`bodyExit`/`stuckBodySummary`/`closeGraceBudgetNanos`/`awaitSettledUninterruptibly`/`warnUnfinished`；
`TaskObservation.forTask`/`unattributed`/`publishSkipped`/`readOnly`；`Task.observationView`。

### 2.2 闸门实测（`/tmp/vf-proposals/p3/`）

做法：`git archive dd12641 src/main/java` 解出后，用带首尾行断言的脚本（`delete.py`）按阶段删除，每个阶段都用
JDK 25 的 `javac --release 8` 对 guava 33.6.0-jre、failureaccess 1.0.3、transmittable-thread-local 2.14.5、
jspecify 1.0.1 编译。基线本身 javac 与 `-Xdoclint:reference/private` 均通过。本节行号都指 dd12641。

| 阶段 | 内容 | `javac` | doclint reference | 删 / 加 |
|---|---|---|---|---|
| A | 只删 §2.1 底稿 | **失败**，3 处 | — | 833 / 0 |
| A1 | A + 3 处被迫改动 | 通过 | **9 处悬空 `{@link}`** | 836 / 6 |
| B | A1 + 传递性死代码 + javadoc 修正 + 未用 import | 通过 | 通过 | 992 / 20 |
| **B'（推荐）** | B，但保留 `ExecutionPhaseHintFuture.callableReleased()` | 通过 | 通过 | **987 / 20** |

**被迫多改的 3 处（Q8 没有列出）：**

1. `TaskBatch implements AutoCloseable` 与 `TaskGroup implements AutoCloseable`（2 处）：删 `close()` 后必须一起删，
   否则报「does not override abstract method close()」。
2. `TaskGroup.prepare` 的 `new ArrayList<>(group.members.values())`（dd12641 `TaskGroup.java:704`）。**Q8 说
   `members` 映射「只被 findMember/members() 读」不成立**：生产路径用它把成员视图交给
   `env.retainUntilComplete(...)`。改为遍历 `orderedMembers` 取 `member.view`，内容与顺序不变。

**传递性死代码（底稿删除后零调用者，javac 不报，B 阶段一并删除）：**

- `TaskGroup`：`groupId()`、`groupName()`（主源码零调用，字段仍被 `snapshot()`/日志使用；测试改读
  `TaskGroupResult` 的同名方法）；`memberAt`、`memberNamed`、`requireDeclaredType`、`castView`；
  `groupSummary(SettableFuture)`；未用 import `Optional`。
- 被删 `completionTask` 构造是唯一调用链的：`TaskCompletion.groupSummary`、`TaskObservation.of(Supplier, ListenableFuture)`、
  四参 `Task.of(...)`。
- `BodyCompletionTracker`：`awaitNanos`、`isDone()`（**Q8 把 `isDone` 列为不能删，删除集落地后它零调用**）、
  字段 `identityUnits`/`lastRegisteredUnit` 及 `register` 里维护它们的分支、import `Sets`/`Set`。
- `Par`：字段 `NO_SUBMISSION`（唯一读者是 `submitWhileOpen`），import `Collections`。
- 其余未用 import：`TaskBatch` 3 个、`TaskGroupReport` 5 个。

**悬空引用（只有 doclint 能发现，9 处）：** `TaskFuture.java:89`（`TaskBatch#awaitBodyCompletion`）、
`TaskGroup.java:41-42,53`（类 javadoc 里的 `futureAt`/`futureOf`/`members`/`terminalFuture`，4 处）、
`TaskCompletion.java:159`（随 `groupSummary` 删除）、`TaskGroupDefinition.java:63`（`TaskGroup#close()`）、
`TaskBatch.java:298-299` 五参 `of` 的 `@param token`/`@param closeGrace`（`#close()`，2 处）。doclint 看不到、但按 AGENTS.md
「touching the lines」一并处理的：`Par.java:504` 的行内注释「Same reason as Par.submit」（删除，理由已在
`prepareUnderResolvedScope` 的 javadoc 里）；`BodyCompletionTracker` 类 javadoc 的自等待段落与
`closeGraceBudgetNanos` 指向 `TaskGroup.close()` 的首句（改写）。新增行中没有 `//`。

### 2.3 行数（B'）

| 文件 | 基线 → 删后 | 净变 |
|---|---|---|
| `TaskGroup` | 1268 → 949 | -319 |
| `TaskBatch` | 489 → 219 | -270 |
| `BodyCompletionTracker` | 343 → 167 | -176 |
| `TaskGroupReport` | 174 → 89 | -85 |
| `Par` | 596 → 520 | -76 |
| `TaskCompletion` / `TaskObservation` / `Task` / `TaskFuture` | — | -20 / -11 / -9 / -1 |
| **合计** | 根包 11,183 → 10,216 | **-967** |

删除的 987 行里，javadoc/注释 479、空行 55、代码 453。kimi 的 520–530 行估计只覆盖 `TaskGroup` 访问面、`TaskBatch`
测试专用面、`checkNotSelfAwait`/`awaitBounded` 与 `callableReleased()`，没有包括 `Par.submit`、`TaskGroupReport`、
tracker 的另外三个等待方法和传递性死代码。

### 2.4 公开面

`javap -public` 全量比对 dd12641 与 B'：差异只出现在包私有的 `TaskBatch`/`TaskGroup` 上。用仓库自己的
`scripts/check-api-surface.py`（导入其 `extract`/`diff`）比较两棵 class 树：removed=0、added=0。本条**不需要迁移
条目**，`PublicApiSurfaceTest` 不变。

## 3. 现状代码 / 改后代码与被消除的失败模式

用户代码：现状与改后相同，零改动（公开 API 不变，§2.4）。内核侧除删除外唯一的改写是 §2.2 第 2 条，现状：

```java
List<ListenableFuture<?>> retained = new ArrayList<>(group.members.values());
```

改后（内容与顺序不变）：

```java
List<ListenableFuture<?>> retained = new ArrayList<>(group.membersAndTerminal.size());
for (MemberState member : group.orderedMembers) {
    retained.add(member.view);
}
```

测试侧的现状/改后写法见 §4.3。本条消除的都是维护层面的失败模式：

1. 测试绿灯证明的是用户走不到的路径（§1 理由 1），给出虚假的覆盖信心。
2. 生产路径为测试付费：每次组运行的 `completionTask`/`valuesView`/`members` 分配与 `groupSummary` 计算，以及
   每个 tracker 的 identity 集合维护（§1 理由 2）。
3. 契约 MUST 条款约束用户触达不到的句柄（`close()` 的宽限/中断/幂等、`awaitBodyCompletion` 的校验顺序），
   维护者只能为它们写代码、写测试、做 PIT 分类。
4. 文档误导：`Par.submit` 自称一元入口；`AGENTS.md:53` 把 `Par.submit` 列为三个入口之一。

## 4. 测试迁移

### 4.1 受影响范围（编译器给出，精确）

把 dd12641 的 77 个测试文件（JUnit 5.14.4、AssertJ 3.27.7、Awaitility 4.3.0、Mockito 5.23.0，`--release 11`）对 B'
编译：28 个文件、642 处错误；按错误行所在的方法归并，得到 262 个测试方法和 4 个辅助方法
（`ScopedTaskContractTest.submitSingle`/`lastGroupResult`、`TaskGroupCombineTest.declaredTerminal`、
`TaskGroupTest.foreignMemberName`）。基线对 dd12641 主源码编译测试为 0 错误，所以这些全部由删除引起。

### 4.2 四条路线

| 路线 | 适用 | 做法 |
|---|---|---|
| **M 迁到公开面** | 断言的是 `map`/`runAll` 能观察到的性质 | `submitAll()` → `runAll()`；`submitBatch()` → `map()`；`completionFuture().get()` → 返回的结果本身；`futureOf(n).get()` → `resultOf(n).valueOrThrow()`；`members().get(n)` → `TaskGroupResult.members()`；`report()`/`stateCounts()` → `TaskBatchResult.report()`（#6 采纳后为 `outcomeCounts()`）；`groupId()/groupName()` → `TaskGroupResult` 同名方法；`Par.submit(...)` → 单成员组。「提交 → latch 编排 → join」形态要把编排移进 body 内的栅栏或另开控制线程，因为 `runAll()`/`map()` 会占住调用线程（`TaskGroupConvergenceTest` 的两个轮次探针就是这样）；凡是 `finish()` 仍然执行的规则（宽限派生、宽限耗尽告警、写入可见性、名额释放），用 `bodyCompletionConfirmed()`/`unfinishedBodies()` 断言 |
| **E executor 通道** | 需要从外部取消运行中或排队中的成员，或在成员 future 上挂 listener | 测试注册一个转发式 `ExecutorService`，在 `execute(Runnable)` 里记下收到的 `Runnable`（即引擎 future），由控制线程在 latch 点上 `cancel(true)`；或在它上面挂 listener。见 §4.3 |
| **K 内核层直测** | 测的是内核组件本身 | `SlidingWindowSubmitterTest`（25）：`batch.results()` 改用已持有的 `viewsFor` 视图，`report`/`valuesOrThrow` 改为 `finish()` 后读冻结结果；`TaskBatchResultTest` 的 13 个归因用例：`TaskBatch.of(...).finish()` 后读冻结结果（已在 B' 上用 `report_attributesCancelledSiblingsToFailFast` 的同一组断言验证通过）；`BodyCompletionBarrierInterruptTest` 的 5 个：改测 `awaitSettledUninterruptibly`（目前没有任何直接测试）；`SynchronousExecutionTest` 的闩锁测试本来就用反射读 `groupToken`，同样反射读 `completion` |
| **D 随 API 删除** | 断言的是被删成员自身的契约 | 自等待守卫；`close()` 的宽限/中断/幂等；`awaitBodyCompletion` 的参数校验与中断；`valuesFuture()` 的只读与取消；`TaskFuture` 视图的转发与 listener executor；组完成观测摘要；`Par.submit` 中与单成员组用例重复的部分；挂在观测 future 上的用户 listener 抛 Error（公开面拿不到这个 future） |

按文件统计（受影响方法数 / 该文件全部测试方法数）。受影响集合由编译器给出，是精确的；路线是按方法名与断言对象
做的初步分类，迁移时逐个复核：

| 测试类 | 受影响 | M | E | K | D |
|---|---|---|---|---|---|
| `TaskGroupTest` | 36/39 | 31 | 4 | 0 | 1 |
| `SlidingWindowSubmitterTest` | 25/25 | 0 | 0 | 25 | 0 |
| `TaskBatchResultBodyCompletionTest` | 21/21 | 9 | 1 | 1 | 10 |
| `TaskBatchResultTest` | 18/18 | 0 | 0 | 13 | 5 |
| `TaskFutureTest` | 18/25 | 9 | 2 | 1 | 6 |
| `TaskGroupBodyCompletionTest` | 17/17 | 7 | 0 | 0 | 10 |
| `ParRuntimeTest` | 14/33 | 13 | 1 | 0 | 0 |
| `GroupDraftContractTest` | 11/16 | 11 | 0 | 0 | 0 |
| `ObservationFutureTest` | 11/11 | 6 | 1 | 0 | 4 |
| `TaskGroupCombineTest` | 11/13 | 11 | 0 | 0 | 0 |
| `ParSubmitTest` | 9/9 | 3 | 0 | 0 | 6 |
| `TaskGroupValuesFutureTest` | 9/9 | 6 | 0 | 0 | 3 |
| `BodyCompletionBarrierInterruptTest` | 8/8 | 0 | 0 | 5 | 3 |
| `GroupDraftLifecycleTest` | 8/11 | 6 | 2 | 0 | 0 |
| `DirectExecutorSafetyTest` | 7/7 | 7 | 0 | 0 | 0 |
| `TaskGraphScopeOwnershipTest` | 6/6 | 6 | 0 | 0 | 0 |
| `InlineSubmissionLivenessTest` | 5/5 | 5 | 0 | 0 | 0 |
| `TaskGroupCombineInlineGuardTest` | 5/5 | 5 | 0 | 0 | 0 |
| `ScopedTaskContractTest` | 4/7 | 3 | 1 | 0 | 0 |
| `DefaultEnqueuePolicyTest` | 3/3 | 3 | 0 | 0 | 0 |
| `TaskGraphExportTest` | 3/7 | 3 | 0 | 0 | 0 |
| `TaskGroupCombineNestedGuardTest` | 3/3 | 3 | 0 | 0 | 0 |
| `TaskGroupConvergenceTest` | 3/3 | 2 | 1 | 0 | 0 |
| `CallableReferenceReleaseTest` | 2/9 | 0 | 0 | 2 | 0 |
| `TaskGroupCombineGuardCancellationTest` | 2/2 | 1 | 1 | 0 | 0 |
| `SynchronousExecutionTest` | 1/18 | 0 | 0 | 1 | 0 |
| `TaskGraphObservationScopeTest` | 1/10 | 1 | 0 | 0 | 0 |
| `TaskGroupListenerFailureTest` | 1/1 | 0 | 0 | 0 | 1 |
| **合计** | **262** | **151** | **14** | **48** | **49** |

按方法体粗算行数（不含共享辅助方法）：M 约 5.3k、E 约 0.6k、K 约 1.3k、D 约 1.6k。`TaskGroupListenerFailureTest`
唯一的测试走 D，整个文件（75 行）删除。

要特别保留的两处：

- `ParSubmitTest.inertRejectEnqueueIsReportedOncePerParAndNeverForAQueueThatHonorsIt` 是 `Par.warnIfRejectEnqueueInert`
  唯一的测试，迁到 `map`（M）。#9 正在把 `CPU_BOUND` 并进同一个告警：#9 的新测试应写在 `map` 上，不要写在
  `par.submit` 上。
- `CallableReferenceReleaseTest` 保留 `callableReleased()` 后只剩 2 个方法受影响（`results` → 视图）。删掉探针则 9 个都
  受影响，`ExecutionPhaseHintFutureTest` 也会多出 3 个（§5）。

### 4.3 executor 通道：公开面上已有的取消与 listener 入口

`ExecutionPhaseHintFuture.submitPrepared` 调用 `executor.execute(this)`，`ExecutorRuntime` 的 listening 装饰器把
`execute` 原样转给用户的 executor。所以用户注册的 executor 收到的 `Runnable` 就是任务的引擎 future。探针
（`probe/src/p3probe/ExecutorChannelProbe.java`，只用公开 API，对 dd12641）输出：

```text
cancelOnHandoff:    group=GROUP_CANCELLED a=MEMBER_CANCELLED b=GROUP_CANCELLED confirmed=true
cancelWhileRunning: group=GROUP_CANCELLED a=MEMBER_CANCELLED b=GROUP_CANCELLED confirmed=true aObserved=MEMBER_CANCELLED
throwingListener:   cancel() rethrew listener boom；converged group=GROUP_CANCELLED a=MEMBER_CANCELLED b=GROUP_CANCELLED
batchCancelWhileRunning: FAIL_FAST FAIL_FAST FAIL_FAST
```

由此得到三条结论：

1. 成员直消的归因（`MEMBER_CANCELLED` + 兄弟 `GROUP_CANCELLED`）可以从公开面复现，迁移不需要包私有钩子。
2. 在捕获到的 future 上挂一个抛 `Error` 的 direct listener 后，组仍然收敛：`memberCompleted` 的 finally 计数守卫保护
   的是**用户可达**的路径，不是死代码，不能随本条删除。`TaskGroupConvergenceTest.barrierCountsAMemberWhoseCancellationCascadeThrowsAnError`
   因此走 E 而不是 D。
3. 批元素共享一个 token：直接取消任何一个元素，整批都读 `FAIL_FAST`，与 `TaskBatch.report()` 的 javadoc 一致。

迁移样例。下面这段原样嵌进测试类，对 B' 用 `--release 11` 编译运行，两条断言通过，与迁移前
`group.futureOf("cancelled").cancel(true)` 的断言相同。`forwarding` 是测试里约 30 行的转发式
`AbstractExecutorService`，在 `execute` 中先回调、再转交给池：

```java
AtomicReference<Runnable> engine = new AtomicReference<>();
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("worker"), pool)
        .register(ParId.of("recording"), forwarding(pool, command -> engine.compareAndSet(null, command)))
        .build();
CountDownLatch entered = new CountDownLatch(1);
Thread canceller = new Thread(() -> {
    if (Uninterruptibles.awaitUninterruptibly(entered, 5, TimeUnit.SECONDS)) {
        ((Future<?>) engine.get()).cancel(true);
    }
});
canceller.start();
TaskGroupResult<Tuple2<Integer, Integer>, Void> result = runtime.group("cascade", Duration.ofSeconds(5))
        .par("cancelled", runtime.par(ParId.of("recording")), Integer.class, () -> {
            entered.countDown();
            Thread.sleep(10_000);
            return 1;
        })
        .par("sibling", runtime.par(ParId.of("worker")), Integer.class, () -> {
            Thread.sleep(10_000);
            return 2;
        })
        .runAll();
canceller.join();
assertThat(result.resultOf("cancelled").outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELLED);
assertThat(result.resultOf("sibling").outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
```

迁移前的同一断言写法是 `submitAll()` 之后 `group.futureOf("cancelled", TypeToken.of(Integer.class)).cancel(true)`，再
`group.completionFuture().get(...)`。

一个限制：从未交给 executor 的 future（deadline 先到而跳过提交的成员、因成员失败而从未提交的 combine）通道拿
不到。现有三个测试在这类 future 上断言 `callableReleased`（`GroupDraftLifecycleTest.timeoutBeforeRunReleasesTheMemberBodyHolders`、
`TaskGroupBodyCompletionTest.unstartedCombineReleasesItsSlotOnCancellation`、`TaskGroupCombineTest.memberFailureSkipsCombineAndTerminatesTerminalFuture`）。
这三处的释放断言随迁移删除，测试的其余断言走 M。理由：这类 future 只被本次运行的对象图引用，`runAll()` 返回后
整张图不可达，释放与否没有用户可见的后果。

## 5. 唯一偏离底稿：保留 `ExecutionPhaseHintFuture.callableReleased()`

Q8 把它列入删除集。本文建议保留，理由：

1. 它验证一个真实的生产性质：引擎 future 在 body 结束（或确定不会进入）后不再持有用户闭包。这个性质对用户
   可见，因为已取消、尚未出队的引擎 future 会留在用户的池队列里（`ParRuntimeTest.cancelledQueueEntriesRetainCapacityUntilTheExecutorOwnerPurges`
   正是从池队列里取 future 来断言）。
2. 它是只读字段探针，三行，不分配、不改行为，也不构成一套平行 API。§1 退役的是句柄面，不是这类探针。
3. 替代方案更差：`WeakReference` + `System.gc()` 轮询不确定且慢（依赖里没有 guava-testlib 的 `GcFinalization`）；
   反射读私有字段 `callable` 可行（`SynchronousExecutionTest` 已经在反射读 `groupToken`），但字段改名要到运行期才
   失败，而具名探针在编译期就会失败。
4. 保留它，受影响的测试方法少 10 个（B 对 B'：`CallableReferenceReleaseTest` 9 → 2，`ExecutionPhaseHintFutureTest`
   3 → 0）。

`TaskGroup.callableReleasedAt`/`callableReleased(String)` 照删：它们只是按名/按位转发，测试经 §4.3 的通道拿到引擎
future 后直接调用探针。

## 6. 同一提交要改的契约与文档，以及后续项

按 `design/AGENTS.md` 第 3、6 条（取代即原地改写，契约与实现同一提交）：

- `synchronous-scope-exit.md:90-92` 改写为：「TaskGroup、TaskFuture、TaskBatch、TaskGroupReport 均包私有，只保留
  执行与收敛所需的成员；没有句柄式访问面。内核测试经公开入口或测试自己注册的 executor 验证。」§6 验证记录中
  「原有并发测试通过包私有入口继续验证」一句同步改写。
- `task-group-cancellation.md` §8.5：删除 `close()` 的全部 MUST 条款（自等待、取消 + grace 等待、中断、幂等）与
  `awaitBodyCompletion(Duration)` 的校验顺序段，保留「`finish()` 的清理预算规则」与 body-exit 状态机。
- `task-group-lifecycle.md:273,277`：去掉包私有 `awaitBodyCompletion(...)`/`close()` 的描述。
- `interruption-contract.md` §5.2 的「自等待检查」一环，以及必测矩阵第 7 行（`awaitBodyCompletion` 带标志调用）：
  删除或改指 `ParRuntime.awaitQuiescence`（矩阵第 10 行已覆盖它）。
- `AGENTS.md:52-55` 的 bind-before-submit 不变量：「`Par.submit`, `Par.map`, and `TaskGroup.start`」去掉 `Par.submit`。
- dated record 不改：`cancel-intent-clock-domain-validation-2026-10-08.md` 的 PIT 分类行、`group-one-shot` §2 的改前
  示例、`migration-v0.2.md`。
- `decision-log.md` 新增一行：推翻 `:90-92` 的保留条款，并写明保留 `callableReleased()` 的理由（§5）。

后续项（不在本批，删除集落地后才成立，逐项 grep 确认过主源码零调用者）：

- `GroupDraft.*.submitAll()` 与 `ParRuntime.groupDraft*` 在测试迁走后只剩 `runAll()` 一个调用者，可以内联；
  `Par.submitBatch` 同理，只剩 `map`。
- `TaskGroup.values` 的失败/取消发布分支与 `converge()` 里「caller's listener」的注释：`valuesView` 删除后，只有
  `finish()` 在成功时读 `values`。try/catch 本身仍保护 `values.set` 之前的失败，保留；javadoc 改写。
- `TaskGroupReport.members` 快照：删除集落地后只剩 `failedTaskSnapshot()` 用它找一个失败，正是 F6「汇合直接产出
  `TaskGroupResult` 所需字段」的入口（§7）。
- `TaskObservation.taskName`/`unitId` 的 `@Nullable` 与 `publishSkipped` 的 `Verify`：custom-snapshot 形态删除后恒非空，
  可以收紧（涉及 NullAway，要用 `mvn` 编译确认）。
- F1 里不在 Q8 集合内、主源码同样零调用的：`Task.transform`/`catching`/`withTimeout`/`derived`/`fluent`、
  `ScopedCallable.delegateReleased()`、`TaskGroup.RunBindings.taskSlotCleared`/`combineSlotCleared`、
  `SlidingWindowSubmitter` 两参构造、`ExecutionPhaseHintFuture.create(Callable)` 单参形态、
  `ExecutorRuntime.submissionExecutorIsAdapter()`/`suppliedExecutor()`（后者被 `ExecutorRuntime:57` 的 javadoc 引用）、
  `ExecutorIdentity.suppliedExecutor()`、`ParRuntime.runtimes()`/`runtimesByIdentity()`、`MultiTaskContext.taskCount()`。
  它们是否并入本批，列在 §10。

## 7. 与 #11 及在途免拍板项的先后与耦合

brief 的前提是：#11（F6/F7/F8 中纯包私有、且不在删除集里的部分）不碰删除集里的任何成员。这个前提成立，但它
带来一个后果：#11 有三项在这个约束下**做不完**。

| #11 子项 | 与删除集的关系 | 在不碰删除集时能否完成 |
|---|---|---|
| F6 删 `Par` 转发器（`executorIdentity()`/`submissionExecutor()`/`prepareGroupTask()`）、`TaskGraphData`→`Snapshot` 转发、`MultiTaskContext` 与 token 的双份 deadline | 只在同一文件里相邻（`TaskGroup.prepare` 用转发器，与 §2.2 的 `retained` 改写同在一个方法的不同块） | 能；冲突按成员名重放即可解 |
| F7 listening 装饰器、`DispatchingScheduledExecutorService`；F8 相位通知收缩 | 无交集 | 能 |
| F6 删除 `TaskGroupReport`，汇合直接产出 `TaskGroupResult` 字段 | `completionFuture()` 的返回类型、`TaskCompletion.groupSummary(TaskGroupReport)`、`TaskGroupReport` 的 5 个死方法都在删除集内 | **不能** |
| F7 删除 `TaskFuture` 接口、直接用 `Task` | `TaskFuture` 是 `Par.submit`、`TaskGroup.futureAt/futureOf/members/findMember/terminalFuture/completionFuture`、`TaskBatch.results()` 的返回类型（主源码 31 处非 javadoc 引用，删除集落地后剩 12 处） | **不能** |
| F7 `Resolution` 收敛为固定组合 | 6 个 `MultiTaskContext.resolution(...)` 调用点中有 1 个是 `Par.submitWhileOpen` | 只能做成 5/6 |

推荐顺序：

1. #11 中不重叠的部分（上表前两行）照常进行。
2. 本条（#10）。若 #6 同批采纳，#6 的测试迁移先于本条（`batch-group-read-surface-symmetry-proposal.md` §8）。
3. #11 的三项尾巴：`TaskGroupReport` 删除、`TaskFuture` 删除、`Resolution` 收敛。届时 `TaskFuture` 只剩 12 处引用、
   `TaskGroupReport` 只剩 7 处。

每一步独立跑全量 `mvn test`。`delete.py` 用的是 dd12641 的行号，只是闸门证据；落地时以 §2 的**成员清单**为准，在
#11 之后的 HEAD 上重放，然后重跑 §8 的闸门。

在途免拍板项：

- **#3**（删 `"NA"` 哨兵）：三处字面量中有一处在 `Par.submitWhileOpen:417`，那段代码会被本条整体删除。#3 先落地时
  这一处是白改，不影响正确性。
- **#9**（`CPU_BOUND` 并入一次性告警）：现有唯一测试在 `ParSubmitTest`，经 `par.submit` 触发；#9 的新测试写在 `map`
  上（§4.2）。
- **#1**：修复树 `fix/land-pr56-k3-onto-dev` 相对 dd12641 改了 `TaskBatch`/`TaskGroup`/`Task`。逐块核对 diff：改动落在
  `freezeResults`/`finishCombined` 的 `fromTask` 调用、`TaskGroup.start`/`memberCompleted`/分类与收敛块、`Task` 的读取方法，
  没有一块落在删除集成员上。本条在 #1 之上按成员名重放即可。
- **#2**：改的是 `freezeResults` 的分类时刻；本条删除的 `TaskBatch.valuesOrThrow()`/`report()` 不经过 `freezeResults`，
  两者不改对方的代码。

## 8. 闸门复现与未验证边界

```bash
cd /tmp/vf-proposals/p3 && . ./env.sh
python3 delete.py base runB B && cp -R runB runB2
cp base/src/main/java/io/github/monadrome/parallelinscope/ExecutionPhaseHintFuture.java \
   runB2/src/main/java/io/github/monadrome/parallelinscope/
$JAVAC --release 8 -Xdoclint:reference/private -cp "$MAINCP" -d out-runB2 $(find runB2/src/main/java -name '*.java')
$JAVAC --release 11 -Xmaxerrs 100000 -cp "out-runB2:$MAINCP:$TESTCP" -d out-runB2-test \
   $(find base/src/test/java -name '*.java') > runB2-test-javac.log 2>&1
python3 testmap.py runB2-test-javac.log > testmap-B2.out && python3 classify.py testmap-B2.out
```

`delete.py` 每一段删除都断言首行内容和下一行内容，行号漂移时会立即失败，不会静默删错。

未验证：

- 没有跑 Error Prone/NullAway（仓库构建会跑）。删除集只删代码，新增的 20 行里只有 `retained` 循环是代码，不涉及
  `@Nullable`；但按 AGENTS.md，权威闸门是落地提交上的 `mvn test`。按 brief 不在仓库与 `/tmp/vf-impl` 里跑 Maven；
  测试尚未迁移，现在也没有可跑的测试树。
- 测试路线分类（§4.2 表）是逐方法读名称与断言对象得出的初步判断；受影响集合与计数是编译器给出的。
- 没有做性能基准（§1 理由 2 不以性能为论据）。

## 9. 已排除的替代方案（供 decision-log）

| 方案 | 否决理由 |
|---|---|
| 维持 `:90-92` 的保留条款 | §1 的四条理由：假覆盖、生产路径为测试付费、挡住 #11 的三项、文档为不可达代码背书 |
| 保留一个窄的包私有钩子（如按名取消成员），其余删除 | §4.3 实测表明公开面已有等价通道（用户 executor 收到引擎 future），钩子是多余概念 |
| 连同运行对象一起删除 | 运行对象承载取消、调度与观测屏障，是 `finish()` 的输入，不是测试面 |
| 按 Q8 删除 `ExecutionPhaseHintFuture.callableReleased()` | §5：它验证用户可见的保留性质，替代方案不确定或推迟失败到运行期 |
| 迁移测试时用反射读取被删成员的残留字段 | 只允许用于已有先例的闩锁注入（`groupToken`/`completion`），不用于替代被删的查询面 |

## 10. 待拍板点

1. **推翻 `synchronous-scope-exit.md:90-92` 的保留条款**，按 §2 的删除集（B'）退役，§6 的契约改写同提交落地。
2. **`ExecutionPhaseHintFuture.callableReleased()`**：保留（推荐）/ 删除并改用反射 / 删除并改用弱引用。
3. **测试迁移方式**：按 §4.2 的 M/E/K/D 四条路线，D 路约 49 个方法随 API 删除，不新增任何测试钩子；从未交给
   executor 的 future 上的三处释放断言随迁移删除（§4.3 末段）。
4. **§6 后续项是否并入本批**：推荐不并入，删除集已经是一次可独立评审的变更；后续项在本批落地后逐项做，每项都可以
   用同一套闸门验证。
5. **落地顺序**：#11 不重叠部分 → 本条（#6 若采纳，其测试迁移在前）→ #11 三项尾巴（§7）。
6. **decision-log**：写入推翻理由与 §5 的保留理由。

