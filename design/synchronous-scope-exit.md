# 仅同步出口的并行作用域

> 公开执行仅同步返回；内部并行。退出策略采用现有有界清理，保留 closeGrace，
> 不采用无限等待执行退出。本文是公开执行/结果形状的权威契约。

## 1. 决策与边界

Par.map / group.runAll 等待全部任务结果确定，再尝试有界清理，返回冻结数据。
ImmediateResult<T> 类似 Either<Throwable,T>，不实现 Future；asFuture 提供已完成适配。

两个边界独立：

- 结果确定：任务成功、失败或取消已经落定。
- 执行退出：直接任务退出 finally，或被原子确定不会再进入执行。

取消可能先完成结果而执行仍在关闭资源。Java 不能强制停止任意 Callable/close，
因此没有硬性“资源一定关闭”或墙钟耗时上限；executor.execute 也可能同步阻塞在用户执行。
有界清理耗尽时返回 unexitedExecutions、缺失最终观测，并告警；不伪造退出时刻。

executionExitConfirmed 只覆盖直接 batch 元素或 group 成员及 combine，**不递归覆盖子调用**。术语与退出确认的定义见 [中断契约](interruption-contract.md) §1.2（`proposal:P0035`）。
嵌套执行可能返回未确认退出的结果，父执行随后成功退出。释放共享资源必须确认全部使用者，
包括分别检查相关子调用；外部线程/异步 IO 不属于库任务代码边界。runtime 关闭并成功
awaitQuiescence 可在应用 shutdown 边界确认全部已接纳执行。

## 2. 最佳用户代码与消除的失败模式

改前：

```java
List<Price> loadPrices(List<String> skus) throws ExecutionException {
    try (TaskBatchResult<Price> batch = par.map(skus, this::fetchPrice, options)) {
        return batch.valuesOrThrow();
    }
}
```

改后：

```java
List<Price> loadPrices(List<String> skus) throws ExecutionException {
    return par.map(skus, this::fetchPrice, options).valuesOrThrow();
}
```

组改前：

```java
try (TaskGroup<Tuple2<User, Account>, Profile> group = runtime
        .group("profile", Duration.ofSeconds(3))
        .par("user", dbPar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .submitAll()) {
    return group.terminalFuture().get().get();
}
```

组改后：

```java
return runtime.group("profile", Duration.ofSeconds(3))
        .par(TaskKey.<User>named("user"), dbPar, () -> loadUser(id))
        .par(TaskKey.<Account>named("account"), httpPar, () -> loadAccount(id))
        .combine(TaskKey.<Profile>named("profile"), httpPar,
                values -> buildProfile(values.first(), values.second()))
        .runAll()
        .terminalValueOrThrow();
```

消除：忘记等待、忘记每次执行清理、把运行句柄当成已完成值。
清理仍不承诺全部执行退出，结果显式暴露此事实。单任务迁移到单成员组；不新增另一套 unary
公开 API，不提供 async 变体。公开接口与签名破坏性变更无兼容 shim。

请求取消的改前最佳代码只能在检查点轮询应用标志：

```java
par.map(ids, id -> {
    if (requestCancelled.get()) throw new CancellationException();
    return fetch(id);
}, options);
```

改后由调用方持有的 `ListenableFuture` 接入整个作用域：

```java
SettableFuture<Void> disconnect = SettableFuture.create();
onClientDisconnect(() -> disconnect.set(null));
par.map(ids, this::fetch,
        BatchOptions.timeout("fetch", timeout).cancelOn(disconnect));
```

消除的失败模式是检查点之间（尤其阻塞 IO 中）取消不可达；外层 future 的取消也不证明内部同步调用
已经退出。新入口不恢复运行句柄或公开 token，也不把协作中断夸大为任意 IO 已停止。

## 3. 公开结果

| API | 语义 |
|---|---|
| Par.map | 同步 TaskBatchResult<T> |
| 各 group stage.runAll | 同步 TaskGroupResult<V,R>，保持一次性、创建线程、阶段 guard |
| Batch.results | 输入顺序 List<ImmediateResult<T>> |
| Group.results / resultOf / resultAt | 名称/位置未类型化终态容器 |
| Group.resultOf(TaskKey) | 按原声明 key 的对象身份即时读取成员或终端容器，规则见组声明契约 §4.1 |
| Group.valuesResult | ImmediateResult<GroupValues<V>>；combine 失败也使聚合失败 |
| Group.terminalResult | @Nullable ImmediateResult<R>，null 仅表示无 combine |
| valuesOrThrow / terminalValueOrThrow / orThrow | 即时读取，不等待，不消费标志 |
| executionExitConfirmed / unexitedExecutions | 返回时直接执行退出状态，浅不可变、不轮询 |
| Batch.completions | 输入顺序 @Nullable TaskCompletion 列表，缺失项为 null |
| Group.members / terminal | 可用最终快照，缺失成员省略、缺失 combine 为 null |

TaskGroup、TaskFuture、TaskBatch、TaskGroupReport 均包私有。Par.submit/submitBatch 仅供
内部内核；公开结果不持有执行器、token、future 或用户 Callable。内部保留旧运行对象供
取消、调度、观测屏障及既有并发测试。组结果持有纯数据的内部收敛报告，公开 member
观测从完成的观测信号重建，保留真实成功值和最终时间。计数覆盖全部任务，即使最终观测缺失。

**非成功公开 ImmediateResult 一律携带非 null throwable；成功结果可为 null，且 null 值合法。**
failed 要求非 SUCCESS/RUNNING outcome 与非 null Throwable；valueOrThrow 的 Left 统一抛
ExecutionException，cause 为存储的原对象。成员结果经内核归一化：USER_FAILURE/SUBMISSION_FAILURE
与已有 CancellationException 保留原对象，其余取消终态冻结为带任务名/归因的
LeanCancellationException（原异常保留在 cause 链）。成功 null 与失败由 outcome 区分。

**组级 valuesResult 的例外**：组已有成员或 terminal combine 记录失败时，容器优先携带该已记录
失败（业务异常原样，不归一化），即使组 outcome 是取消形状（含 TIMEOUT）也如此——容器在
finish() 直接按冻结 outcome 构造，不走成员结果的归一化路径。只有组无任何已记录失败时，容器
才携带按 outcome 命名的 LeanCancellationException。因此 adapter 只承诺"存储的原对象作为
ExecutionException.cause"，不承诺异常类型；取消形状不等于 CancellationException cause。

聚合读取（`TaskBatchResult`、`BatchCombinedResult`、`TaskGroupResult`）共用一条规则。`orThrow()` 是每种
结果唯一的失败闸门，`valuesOrThrow()` 与 `terminalValueOrThrow()` 先经过它。选中的失败：批取输入顺序首个
`USER_FAILURE`/`SUBMISSION_FAILURE` 元素；批+combine 先取元素、再取 combine 自身，因此元素失败不会被报成
combine 的 FAIL_FAST 取消；组取已记录的失败成员或 combine。选中失败是 `RuntimeException`/`Error` 时原样抛出，
checked 时包成 `CompletionException`（消息含作用域名与任务定位）；没有记录失败而 outcome 非 `SUCCESS` 时抛
`CancellationException`。执行自抛的 `CancellationException` 归 `USER_FAILURE`，原样抛出后与框架取消同类型，
区分要读 `outcome()`。单任务容器 `ImmediateResult.valueOrThrow()` 不是聚合读取，保持受检：作用域级 join
非受检、单任务 Either 受检，与 JDK 25 `StructuredTaskScope.join()` 和 `Future.get()` 的分工一致。
结果函数不重跑执行。
类型化读取使用原 TaskKey；不保留 TypeToken 或 raw class 校验。左嵌套 Tuple2、nullable 值与空组保持。

批次失败检查在一次扫描中记住首个取消项，遇到首个已记录执行失败时立即抛出，只有整遍扫描
未发现执行失败才抛取消。成功值在失败闸门通过后另行收集。排除边检查边收集的单遍方案：
它会在后续元素失败或取消时先分配并丢弃整个值列表，且本地测量未显示中等批量的稳定收益。
减少失败检查的重复扫描即可缩短大列表读取，同时保持失败读取不投机分配值列表；不加缓存或索引。
历史来源：`proposal:P0007`；统计、值投影与查询的测量候选在
[P0027](proposal/P0027-result-projection-measurement.md)。

asFuture 返回 ListenableFuture<@Nullable T>：始终 done、cancel=false、isCancelled=false。
库执行产生的取消是 failed future：无已记录失败时 cause 为归一化取消异常；组聚合已有记录失败时
cause 为该存储失败（见上）。手动 failed(outcome, failure) 保留给定异常，outcome 标签不转换异常类型。
两个 get 使用 Futures.getDone，立即读且保留中断标志；timed get 校验非 null TimeUnit，
不做超时等待。listener 用消费者 executor，处于业务资源 scope 外。

**四类终态消费**：`ImmediateResult.fold(onValue, onUserFailure, onSubmissionFailure, onCancelled)` 按
outcome 分支，throwable 只是载荷：`SUCCESS` 走 onValue（值可为 null），`USER_FAILURE` 走
onUserFailure，`SUBMISSION_FAILURE` 走 onSubmissionFailure，其余四种取消形状走 onCancelled 并带
outcome 与存储的 throwable。执行失败与取消的切分与聚合读取的失败选取同一口径。onCancelled 的
throwable MUST NOT 被承诺为 `CancellationException`：组级 valuesResult 的例外与手动
`failed(outcome, failure)` 都保留原异常。`RUNNING` 与未归类的 outcome MUST 落到 `AssertionError`，
不被 default 分支吸收：新增 `TaskOutcome` 值时遍历全部 outcome 的测试先变红，迫使显式归类。
四个处理器都 MUST 非 null；fold 与 `successful()` 只读冻结数据，不等待、不消费中断。被否决的
平行快照类型、typestate 链与单任务获取入口见 [decision-log.md](decision-log.md)。历史来源：
`proposal:P0019`；四类终态分派的落地来源：`proposal:P0037`。

### 输入快照与观测屏障

非空批次输入 MUST 在任何任务准备前浅复制为输入顺序的不可变列表，包含原本为 List 的输入，
避免顺序链表逐项下标读取的平方级成本。输入元素 MUST 非 null；复制失败 MUST 结束已经准入的
单元 token，释放其父传播链接，而不是留下永不绑定的运行中子单元。复制仍在该单元 deadline
预算内；不承诺无同步并发修改普通集合时的快照一致性。null 集合与空集合保持各入口原有规则，
成功 null 结果不变；迁移见 0.4 指南。

用户代码改前改后均为 `par.map(items, mapper, options).valuesOrThrow()`；移除的是顺序输入的重复
遍历，以及只等待观测发布却提前收集完整观测列表的成本，不增加公开入口。

同步冻结 MUST 等待元素观测发布或耗尽既有清理预算。未请求内部完整观测列表时，这个屏障
MUST NOT 收集每个观测值或构造完整聚合结果列表；使用不收集值的 Guava 成功组合，观测构造
异常 MUST 保持可见，不能把失败输入当作成功发布。完整列表只在内部观测入口被请求时惰性
构造，其 future MUST 保持引用稳定、忽略取消，列表仍只读并保留原单元素观测对象。
内部 `awaitExecutionExit` 返回 true 时，既有完整列表 future 也 MUST 已完成；只等待轻量
屏障不足以保证随后注册的完整列表监听器已发布。

最终快照仍按每个元素的 settle pin 冻结结果，并从可用观测读取最终时间；MUST NOT 用较晚的
共享 token 状态替换落定归因，也 MUST NOT 在返回后补齐缺失观测。执行退出、future 终态、
观测发布三者保持独立；执行/观测清理共用一个预算起点。仅惰性化同一个聚合列表而继续在
同步出口等待它，不能消除主路径收集成本；直接复用所有原观测也会丢失归因校正。

## 4. 中断与有界清理

map/runAll 不声明 InterruptedException；等待阶段用 Guava Uninterruptibles，退出恢复标志。
caller interrupt 不转成结果 Left、不触发 scope 取消，不跳过或重置清理预算。
原 P2 阻塞方法规范对这两个执行入口明确例外；runtime.awaitQuiescence 仍传播中断。
库保管调用线程的中断标志而不解释它：返回后的处理和把外部取消接入 `cancelOn` 是调用方的责任。
依据、用户义务与被排除的「调用方中断即作用域取消」方案见
[中断契约「调用方中断不是取消信号」](interruption-contract.md#22-调用方中断不是取消信号)。

deadline、fail-fast、parent token、显式 `cancelOn` 请求来源、runner interruption、checkpoint 保持；
所有入口仍 bind-before-submit，prepared future 视图从创建即绑定，不恢复 placeholder/later-bind。
运行在调用线程的 direct/CallerRuns 执行继续按既有借用线程政策隔离入口标志；不声称
inline 执行期间新收到的外部中断一定保留。只读结果/adapter 不消费标志。

每项结果与 submission loop settled 后开始一次 cleanup budget：
显式 closeGrace，或剩余 deadline；多个执行/观测屏障共享预算。
零 grace 单次检查，无无限 join。超时不延长任务执行资格、不恢复被取消任务。
显式 timeout（`BatchOptions.timeout`、`TaskOptions.timeout`、`ParRuntime.group`）MUST 解析为有限
deadline：和溢出时钳制在 `Long.MAX_VALUE - 1`，MUST NOT 解析成「无 deadline」哨兵，否则不布防定时器、
默认清理预算归零，更大的 timeout 反而得到更小的清理等待。哨兵只能经继承到达；公开入口都有 deadline。
时钟读数本身已在钳制点时，钳制后的 deadline 即已过期，真实 `nanoTime()` 到不了这一点。
观测仅从真实完成的信号读取。返回时未确认退出的执行仍由 runtime 的现有信号跟踪。

任务局部资源使用执行内 try-with-resources；库不注册资源、不发现捕获/返回对象。
关闭主异常/suppressed 按 Java 规则；取消先落定后的迟到执行/close 失败在共享 future
执行内核记录原 Throwable（包含 suppressed），logger handler 失败不阻止清理。MUST NOT 按异常类型过滤：
close 独立抛出的 `InterruptedException`/`CancellationException` 也是迟到失败，按类型丢弃会隐藏它。
级别 MUST 按来源判定，判定只读异常对象与本 future 自身的投递标记，MUST NOT 读 token（避免与级联竞态）：
- 无 suppressed 的库来源取消确认（`Checkpoints` 读到已提交 token 后抛出，见
  [中断契约](interruption-contract.md) §3.1）记 `FINE`；
- 无 suppressed 的 `InterruptedException`，且本 future 的取消中断已投递到本次运行（中断契约 §2.4）、
  记录时线程中断标志为清除状态，记 `FINE`。JDK 阻塞调用因中断抛出时会清除标志；标志仍置位说明
  这个异常不是那次中断的消费（例如执行恢复标志后 close 另抛 `InterruptedException`）；
- 其他一律 `WARNING`：带 suppressed、无标记或包装后的取消异常、用户自建的 `CancellationException`、
  未经内核投递的 `InterruptedException`。

接受的残余风险：内核投递之后、标志已清除时，独立抛出的无 suppressed `InterruptedException` 也记
`FINE`；此时取消已提交、结果已落定，降级不隐藏任何结果。被否决：按类型过滤（会隐藏 close 失败）；
新增 `TaskOutcome` 表示「执行读到取消」（见中断契约 §3.1）。`proposal:P0034`
结果归因不因迟到异常被改写。外部 root 取消不再依赖运行句柄或 caller interrupt；
显式 deadline、fail-fast、祖先取消与 `cancelOn` 请求来源是四类执行取消来源，runtime.close
接纳后排空而不取消。请求来源只增加既有 token 的输入，不改变同步返回、清理预算或执行退出确认；
注册、切断与来源所有权规则见 [取消传播契约](cancellation-propagation.md) §5.4；所有取消来源都中断
运行中的执行（同文 §5.1）。
历史来源：`proposal:P0001`。

## 5. 第一性原理

- 减少忘记：入口统一 join/cleanup；未退出显式报告。
- 复用：共享提交/取消/TTL/phase/执行 tracker；只新增 terminal Either 容器和冻结数据。
- 结构继承：保留 parent token/min deadline/TTL install-restore。
- 没有第二套执行管道、隐式 current group、可配置 failure policy。
- 失败形状沿用 TaskOutcome；Batch 同构、Group 异构，单任务复用组。
- 不重开已否决 ThrowingFunction；map 仍标准 Function。

迁移路径与用户代码见 docs/en/migration-v0.3.md、docs/zh/migration-v0.3.md。
执行器等待诊断由 `ParRuntime.executorWaitReport()` 读取（见 [task-graph.md](task-graph.md)），它是运行时诊断，不是运行业务句柄。
queue 不在本次机制变更范围。

## 6. 评审中驳回的主张

各轮独立评审保留的发现已并入上文规则；下列主张经复现或论证后驳回，重提前先回答其中理由：

- 「`fromTask` 可能把成功的 future 冻结成非成功 outcome」：终态 future 的成功读取决定 `SUCCESS`，
  结果在冻结前已经完成，不会因稍后的 token 取消改判。
- 「同池饥饿会让结果等待无限」：有限 deadline 下独立 timer 与 bind-before-submit 仍能取消排队中的
  future；饥饿是执行布局限制，不是同步出口的活性缺陷。不合作的执行与 `execute` 的墙钟耗时限制见 §4。
- 「`SUCCESS` 与已记录失败可能并存」：`SUCCESS` 要求 future 成功，提交失败的 phase claim 排斥执行进入
  任务代码，合法路径不可达；只在内部矛盾输入下表现为异常形状差异。
- 「结果在 `published` 之前已经冻结，第二个 gate 多余」：`converge` 之前构造的是内部
  `TaskGroupReport`，`ImmediateResult` 在 `published` 之后的 `finish` 中冻结；gate 保留后续回调的
  暂停点，不能按错误的冻结顺序移除。
- 「『original cause』可能误指原执行异常」：指南指存储的 Throwable，读取以它为
  `ExecutionException` 的 cause；取消包装下的原执行异常在下一层 cause，两层 identity 均有回归。
- 「deadline 读取测试须先用 latch 证明执行已开始」：断言对象是冻结读取的形状，执行是否已开始都落
  `TIMEOUT`，读取结果相同。
