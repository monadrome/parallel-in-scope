# 仅同步出口的并行作用域

> 公开执行仅同步返回；内部并行。退出策略采用现有有界清理，保留 closeGrace，
> 不采用无限等待任务体退出。本文是公开执行/结果形状的权威契约；§6–§7 是验证与评审记录。

## 1. 决策与边界

Par.map / group.runAll 等待全部任务结果确定，再尝试有界清理，返回冻结数据。
ImmediateResult<T> 类似 Either<Throwable,T>，不实现 Future；asFuture 提供已完成适配。

两个边界独立：

- 结果确定：任务成功、失败或取消已经落定。
- 任务体退出：直接任务退出 finally，或被原子确定不会再进入 body。

取消可能先完成结果而 body 仍在关闭资源。Java 不能强制停止任意 Callable/close，
因此没有硬性“资源一定关闭”或墙钟耗时上限；executor.execute 也可能同步阻塞在用户 body。
有界清理耗尽时返回 unfinishedBodies、缺失最终观测，并告警；不伪造退出时刻。

bodyCompletionConfirmed 只覆盖直接 batch 元素或 group 成员及 combine，**不递归覆盖子调用**。
嵌套执行可能返回未确认退出的结果，父 body 随后成功退出。释放共享资源必须确认全部使用者，
包括分别检查相关子调用；外部线程/异步 IO 不属于库任务体边界。runtime 关闭并成功
awaitQuiescence 可在应用 shutdown 边界确认全部已接纳 body。

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
        .par("user", dbPar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .runAll()
        .terminalValueOrThrow();
```

消除：忘记等待、忘记每次执行清理、把运行句柄当成已完成值。
清理仍不承诺全部 body 退出，结果显式暴露此事实。单任务迁移到单成员组；不新增另一套 unary
公开 API，不提供 async 变体。公开接口与签名破坏性变更无兼容 shim。

## 3. 公开结果

| API | 语义 |
|---|---|
| Par.map | 同步 TaskBatchResult<T> |
| 各 group stage.runAll | 同步 TaskGroupResult<V,R>，保持一次性、创建线程、阶段 guard |
| Batch.results | 输入顺序 List<ImmediateResult<T>> |
| Group.results / resultOf / resultAt | 名称/位置终态容器，TypeToken 精确匹配 |
| Group.valuesResult | ImmediateResult<GroupValues<V>>；combine 失败也使聚合失败 |
| Group.terminalResult | @Nullable ImmediateResult<R>，null 仅表示无 combine |
| valuesOrThrow / terminalValueOrThrow / orThrow | 即时读取，不等待，不消费标志 |
| bodyCompletionConfirmed / unfinishedBodies | 返回时直接 body 退出状态，浅不可变、不轮询 |
| Batch.completions | 输入顺序 @Nullable TaskCompletion 列表，缺失项为 null |
| Group.members / terminal | 可用最终快照，缺失成员省略、缺失 combine 为 null |

TaskGroup、TaskFuture、TaskBatch、TaskGroupReport 均包私有。Par.submit/submitBatch 仅供
内部内核；公开结果不持有执行器、token、future 或用户 Callable。内部保留旧运行对象供
取消、调度、观测屏障及既有并发测试。组结果持有纯数据的内部收敛报告，公开 member
观测从完成的观测信号重建，保留真实成功值和最终时间。计数覆盖全部任务，即使最终观测缺失。

ImmediateResult.succeeded 允许 null；failed 要求非 SUCCESS/RUNNING outcome 与非 null
Throwable。valueOrThrow 的 Left 统一抛 ExecutionException，cause 为存储的原对象。
执行失败优先级和 group 权威归因沿用内核；取消容器冻结稳定的有任务名/归因的异常，
取消形状的任务异常保留 cause 链。成功 null 与失败由 outcome 区分。

Batch 聚合保留 ExecutionException 报执行失败、CancellationException 报纯取消；
Group 聚合保留 unchecked 原样抛、checked 包装 CompletionException。结果函数不重跑 body。
TypeToken、raw class 校验、左嵌套 Tuple2、nullable 值与空组均保持。

asFuture 返回 ListenableFuture<@Nullable T>：始终 done、cancel=false、isCancelled=false。
库执行产生的取消属于 failed future，get/valueOrThrow 的 ExecutionException.cause 为取消异常。
手动 failed(outcome, failure) 保留给定异常，outcome 标签不转换异常类型。
两个 get 使用 Futures.getDone，立即读且保留中断标志；timed get 校验非 null TimeUnit，
不做超时等待。listener 用消费者 executor，处于业务资源 scope 外。

## 4. 中断与有界清理

map/runAll 不声明 InterruptedException；等待阶段用 Guava Uninterruptibles，退出恢复标志。
caller interrupt 不转成结果 Left、不触发 scope 取消，不跳过或重置清理预算。
原 P2 阻塞方法规范对这两个执行入口明确例外；runtime.awaitQuiescence 仍传播中断。

deadline、fail-fast、parent token、runner interruption、checkpoint 保持；所有入口仍
bind-before-submit，prepared future 视图从创建即绑定，不恢复 placeholder/later-bind。
运行在调用线程的 direct/CallerRuns body 继续按既有借用线程政策隔离入口标志；不声称
inline body 期间新收到的外部中断一定保留。只读结果/adapter 不消费标志。

每项结果与 submission loop settled 后开始一次 cleanup budget：
显式 closeGrace，或剩余 deadline；多个 body/观测屏障共享预算。
零 grace 单次检查，无无限 join。超时不延长任务执行资格、不恢复被取消任务。
观测仅从真实完成的信号读取。返回时未确认退出的 body 仍由 runtime 的现有信号跟踪。

任务局部资源使用 body 内 try-with-resources；库不注册资源、不发现捕获/返回对象。
关闭主异常/suppressed 按 Java 规则；取消先落定后的迟到 body/close 失败在共享 future
执行内核记录原 Throwable（包含 suppressed），logger handler 失败不阻止清理。
结果归因不因迟到异常被改写。外部 root 取消不再依赖运行句柄或 caller interrupt；
显式 deadline、fail-fast 与祖先取消是保留的执行取消来源，runtime.close 接纳后排空而不取消。

## 5. 第一性原理

- 减少忘记：入口统一 join/cleanup；未退出显式报告。
- 复用：共享提交/取消/TTL/phase/body tracker；只新增 terminal Either 容器和冻结数据。
- 结构继承：保留 parent token/min deadline/TTL install-restore。
- 没有第二套执行管道、隐式 current group、可配置 failure policy。
- 失败形状沿用 TaskOutcome；Batch 同构、Group 异构，单任务复用组。
- 不重开已否决 ThrowingFunction；map 仍标准 Function。

迁移路径与用户代码见 docs/en/migration-v0.3.md、docs/zh/migration-v0.3.md。
TaskGraphObservationScope.reportFuture 保留：它是 request 诊断，不是运行业务句柄。
queue 不在本次机制变更范围。

## 6. 验证记录

- ImmediateResultTest：全部 Left、成功 null、原 cause、RUNNING 拒绝、带中断标志读取、
  timed get、cancel/isCancelled、listener executor 与 Guava transform。
- SynchronousExecutionTest：map/group 同步出口、中断不取消并恢复、有界清理不跳过、
  忽略中断 body 的诚实缺失观测、类型查询/Tuple2/null combine、fail-fast 失败优先级、
  拒绝归因、资源关闭主/suppressed、取消后的迟到 close 失败及坏 logger、runtime shutdown、
  嵌套 body 确认不传递、三态取消传播前已记录异常的权威归因与 cause 链。
- PublicApiSurfaceTest：仅终态公开 API，没有公开运行句柄或 submitAll/cancel/close/await。
- 原有并发测试通过包私有入口继续验证取消、上下文、bind-before-submit、inline、观测、
  executor/滑窗/图与 runtime 竞态。
- 格式与新增 ImmediateResultTest / SynchronousExecutionTest 定向测试通过。
  最终主库 mvn -o -q test：73 类、789 项，失败/错误/跳过均为 0（包含 queue 测试）。
  mvn -o -q install -DskipTests 完成普通本地安装，JAR、sources、Javadoc 打包通过；未发布。
  最终 demo mvn -o -q test：26 类、63 项；真实 Corretto 8 消费端：1 类、5 项。
  后两者均使用最终安装的 0.3.0-SNAPSHOT，失败/错误/跳过均为 0。
  补充竞态修复后再次完成上述主库、安装、demo 与 Java 8 检查；日志分别为
  target/synchronous-race-final-tests.log、target/synchronous-race-final-install.log、
  demo/target/synchronous-race-consumer-tests.log、
  verification/maven-central-consumer/target/synchronous-race-consumer-tests.log。
- demo 本次改动的全部 Java 文件通过 spotless:check。全模块检查仅有基线已存在的
  A1_CancelTrueInvalidTest、A3_LeanVsFatExceptionTest、G5_BatchHttpCallsTest import 排序问题；
  对照 HEAD 确认，与本次无关，保留原状。
- 本地 Markdown 链接、英文文档检查、git diff --check 通过。

### 6.1 反向验证

仅临时改变对应实现，观察测试失败，再用定位到具体方法的 patch 恢复：

| 临时变更 | 观察 | 日志（本地 target/，不提交） |
|---|---|---|
| asFuture 直接暴露 Guava get 的中断行为 | 1 项失败 | reverse-immediate-adapter.log |
| 中断使同步结果等待提前退出 | 2 项失败 | reverse-interrupted-results.log |
| 中断使清理等待跳过 | 2 项失败 | reverse-interrupted-cleanup.log |
| 隐藏取消后的迟到 close 失败 | 1 项失败 | reverse-late-close.log |
| 过滤无 suppressed 的取消形状 close 异常 | 2 项失败 | reverse-cancel-shaped-close.log |
| combine 读取恒为 null、切断 parent token、丢失取消 cause 包装 | 3 项失败 | reverse-final-contracts.log |
| 跳过 unfinished body 告警 | 2 项因告警缺失报错 | reverse-unfinished-warning.log |
| 冻结时直接返回 recorded failure，跳过权威取消归因包装 | TIMEOUT/FAIL_FAST/CANCELLED 三项均因原 InterruptedException 泄漏失败 | reverse-recorded-cancellation.log |

最后一项同时保护坏 handler 不影响结果的恢复路径。所有临时变更均已恢复；
独立复审再次核对 parent token、terminalValueOrThrow 与 fromTask 的最终代码。

### 6.2 PIT 分类

首轮：592 mutants，506 KILLED、8 TIMED_OUT、48 SURVIVED、30 NO_COVERAGE；
line coverage 94%，PIT 将 KILLED + TIMED_OUT 合计报告为 87%，test strength 91%。
TIMED_OUT 表示变异体使有界测试不能正常完成，不据此声称单项断言检出了全部缺陷。
范围为结果类型、TaskCompletion、TaskBatch/TaskGroup/TaskGroupReport、BodyCompletionTracker、
ExecutionPhaseHintFuture、GroupDraft、Par、ParRuntime；targetTests 为 core，排除 queue。
首轮产物 target/pit-synchronous/mutations.xml，日志 target/synchronous-pit.log。

85+ 门槛按严格 KILLED / 全部 mutants 核对，既不计 TIMED_OUT，也不扣除等价项：
首轮 506/592 = 85.47%，补测重跑 365/410 = 89.02%，均达标。

以下编号是该 XML 的 1-based mutation 顺序，**逐项覆盖全部 78 个存活/未覆盖项**。
E 为按现有合法调用图证明的行为等价，不记为覆盖缺口；G 为未检出的行为变化；
D 为内部/防御分支未覆盖，也不冒称等价；A 为首轮暴露的缺口，补断言后重跑确认 KILLED。
相同源行的 finally 复制体保守按 G/D 记录，没有因为 PIT 的复制体警告全部排除。

| 编号 | 分类 | 依据与处置 |
|---|---|---|
| 185 | A | 公开嵌套 group 的祖先 fail-fast、继承 deadline；新增闩锁测试 |
| 114 | G | prepare 的 empty 判定中 combineSlot==null guard；缺嵌套空组的 parent listener 保留断言 |
| 186 | A | runtime.inFlight 正值缺断言；新增 admitted body 期间检查 |
| 268 | A | unfinished 告警 guard 缺断言；新增 batch/group 日志与坏 handler 检查 |
| 375、376、378、379、400、500、522 | A | 公开组 id/name/start/end/deadline 元数据；新增非空结果检查 |
| 382、399 | A | 名称查询首项之外的索引、results map 内容；新增第二项与不可变 map 检查 |
| 388、389、404 | A | 纯取消 cause、orThrow identity、非 null combine 读取；新增明确断言 |
| 533、534、535、538、539 | A | future 以异常完成且 token 已取消的转换；新增 InterruptedException 原因链测试 |
| 558 | A | batch 纯取消聚合的原 cause；新增容器/聚合异常 identity 检查 |
| 82、372 | E | 上界 guard 改变后，底层 List.get 仍拒绝相同索引且抛同类异常；具体错误文案非稳定契约 |
| 163、166 | E | 将已有 false/true 分别替换为相同常量 |
| 240、241、242、243、244 | E | 同步 finish 调用均忽略 helper 返回 boolean；等待、副作用与异常路径保持 |
| 261 | E | isDone 仅供旧 close 快路径；false 只多做已完成信号的即时 get，中断仍先检查 |
| 426 | E | 合法 ParameterizedType 的 rawType 为 Class，该递归分支不可能找到 TypeVariable |
| 10 | D | 旧内部 awaitBodyCompletion 的观测屏障超时分支；公开结果已无 await |
| 30、95、96、99 | G | cancellation 归因/级联、成员与 combine 的同步 fail-fast 时序；缺少区分变异体的交错断言 |
| 35 | D | values 发布失败且 sink 尚未完成的恢复 guard；已有测试覆盖 listener Error，而非内部装配失败 |
| 123、124 | G/D | combine inline guard（G）与开启图观测时 combine fork（D）的断言/路径不足 |
| 145 | D | 空成员加 combine 的内核防御路径；公开 stage builder 不允许声明此形状 |
| 178、200、211 | G/D | admission-close 竞态（D）与空 batch retain/admission 最后一个退出的条件（G）缺区分断言 |
| 188、325、330 | G | purge 触发时机、graph 条件与内部 executor identity，缺效果断言；机制未在本次改写 |
| 219、227、236、246 | G | 纳秒预算恰好等于零的边界/竞态未检出；不声称所有边界变异等价 |
| 221、223、239 | D | 旧 awaitBounded 非正预算与不可能失败的 body/观测 signal 恢复分支未覆盖 |
| 222 | G | 旧 close 的 await 返回值变化可产生多余告警；日志断言不足 |
| 278、282 | G | lost body claim / 重复 submission failure claim 的返回值缺区分断言 |
| 286、321 | G | executor handoff Error 的消息/触发 guard 缺精确诊断断言 |
| 301、302、306、307、309、310、312、313、315、316、317 | G/D | runner finally 的 phase/interruption 交错及 javac 复制体；存活为 G，未覆盖为 D，未证明等价 |
| 454、470、585 | G/D | 内部 TaskBatch 的 close 文案（G）、旧 aggregate fallback cause（G）、旧 report.toString（D） |
| 474 | G | enqueued 阈值恰好相等，缺精确边界测试 |
| 511、512、513 | D | 旧内部 TaskGroupReport.orThrow 的异常升级；公开结果用独立终态读取逻辑 |

补测后的 PIT 限定重跑 ImmediateResult、TaskGroupResult/Report、TaskGroup、ParRuntime、
BodyCompletionTracker、TaskBatchResult；保留首轮产物，使用 target/pit-synchronous-followup。
命令如下（完全离线，已有依赖）：

```bash
mvn -o -q -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=io.github.monadrome.parallelinscope.ImmediateResult*,io.github.monadrome.parallelinscope.TaskGroupResult*,io.github.monadrome.parallelinscope.TaskGroupReport*,io.github.monadrome.parallelinscope.TaskGroup,io.github.monadrome.parallelinscope.ParRuntime*,io.github.monadrome.parallelinscope.BodyCompletionTracker*,io.github.monadrome.parallelinscope.TaskBatchResult*' \
  '-DtargetTests=io.github.monadrome.parallelinscope.*' \
  '-DexcludedTestClasses=io.github.monadrome.parallelinscope.queue.*' \
  -DreportsDirectory=target/pit-synchronous-followup
```

重跑：410 mutants，365 KILLED、9 TIMED_OUT、26 SURVIVED、10 NO_COVERAGE；
line coverage 94%，报告 detection 91%，test strength 94%。上述 21 个 A 全部 KILLED。
114 实际改变的是 empty 判定，先前误按 parent-token wiring 预期补测；仍然 SURVIVED，
已纠正为 G。切断 parent token 的独立反向验证确实使新增嵌套测试失败，但不是这个 mutant。

所有剩余项按 class/method/descriptor/mutator/bytecode index 与首轮对齐；
下面首轮编号明确覆盖其中 35 项，另有重跑新纳入内部 scheduler adapter 的 1 项：

| 编号（特别标注外均为首轮编号） | 分类 | 重跑结论 |
|---|---|---|
| 82、163、166、240、241、242、243、244、261、372 | E | 10 项保持前述等价依据，其中 242 为 NO_COVERAGE，其余为 SURVIVED |
| 10、35、124、145、178、221、223、239、511、512、513 | D | 11 项保持前述内部/防御分支限制；35、145 为 SURVIVED，其余为 NO_COVERAGE |
| 30、95、96、99、114、123、188、200、211、219、222、227、236、246 | G | 14 项仍存活，保留前述交错、纳秒边界和诊断断言缺口 |
| 重跑 390 | G | DispatchingScheduledExecutorService.awaitTermination 转发的 boolean 缺断言；首轮未纳入该内部 adapter |

G/D 是明确保留的内核、诊断与边界覆盖限制；未将 mutation score 当作无竞态证明。

K3 修复后的定向 PIT：ImmediateResult*（包括匿名 adapter），targetTests 为 core、
排除 queue；24 mutants 全部 KILLED，严格分数 24/24 = 100%，无 SURVIVED、
NO_COVERAGE 或 TIMED_OUT；line coverage 41/43 = 95%。全部 mutant 已核对状态，
无需新增存活项分类。较大范围的前两轮报告与本轮定向报告分别保留。
产物 target/pit-recorded-cancellation/mutations.xml，日志 target/recorded-cancellation-pit.log。

```bash
mvn -o -q -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=io.github.monadrome.parallelinscope.ImmediateResult*' \
  '-DtargetTests=io.github.monadrome.parallelinscope.*' \
  '-DexcludedTestClasses=io.github.monadrome.parallelinscope.queue.*' \
  -DreportsDirectory=target/pit-recorded-cancellation
```

## 7. 独立对抗性评审

第一轮：gpt-6-astra，只读独立预算（上限 12,000 tokens），攻击面：
interleavings、contract/implementation、test quality、Java 8、generics。

R1（P2，保留）：bodyCompletionConfirmed 的共享资源建议未明确 direct-only，
读者可能据父结果释放仍被子 body 使用的资源。评审以编译后的 JShell 复现：
outerConfirmed=true、innerConfirmed=false、innerStillBlocked=true、outerValue=[1]。
修复：公开 Javadoc、用户指南、迁移页明确直接任务范围与子调用分别确认；新增闩锁回归测试。
实际效果为准确资源边界，不改变已定稿的直接 body tracker，不引入递归等待。

非 findings：awaitQuiescence 可中断属于既有应用关闭协议；有界清理与缺失观测是明确取舍。
评审指出取消后 close 失败的日志测试缺口，已添加原 suppressed 异常与坏 handler 回归。
其他取消/退出、观测发布、共享清理预算、准入、适配、Java8/泛型路径未发现确定缺陷。

第二轮：同一 gpt-6-astra 独立预算（上限 12,000 tokens），先读 R1 再审修复与迁移。
R1 已确认修复；保留三项 P2：

- R2：真实 Java 8 消费测试的 lambda 参数 result 与同方法局部变量重名。
  评审 JavacTask.analyze(--release 8) 与主代理真实 JDK 8 Maven 编译均复现。
  修复为 element；消费端全测试通过，实际效果是恢复公开 API 消费验证。
- R3：协作取消参考页与旧迁移页指向已删除的 guide 锚点，idea graveyard 仍推荐内部 Par.submit。
  主代理核对链接与当前公开接口后保留。修复兼容锚点、参考页、单成员组替代建议，
  并迁移 demo 快速上手/监控/汇总/DB 文章；修正 philosophy 中过时的占位 future 描述。
  实际效果是当前公开文档不再指导调用内部入口，历史链接可继续定位即时结果。
- R4：取消之后，close 抛独立的 InterruptedException/CancellationException 且没有 suppressed
  时，日志过滤把它静默丢弃，超过文档的迟到失败诊断承诺。评审 JShell 复现：
  outcome=TIMEOUT、confirmed=true、closeAttempted=true、logRecords=0。
  新增两种异常的回归，旧过滤下两项均失败；移除过滤，记录全部取消后的迟到抛出异常。
  实际效果是关闭异常不因异常类型被隐藏，取消响应异常也可能出现诊断；冻结归因保持。

模块验证另发现并修复：BasicParDemoTest 未声明 main 新增的受检异常；
E1 示例测试在同步 map 返回之后才释放 gate，造成 30 秒超时，现改为任务内测量有限工作的并发度。

第三轮：同一 gpt-6-astra 独立预算（上限 12,000 tokens），确认 R2–R4 修复，保留两项：

- R5（P2）：新增取消形状 close 日志测试在 runAll 后立即读 logged，存在测试竞态。
  ScopedCallable 先发布 body exit/观测，执行内核随后日志发布。评审 gated-handler 复现：
  resultReturned=true、confirmed=true、handlerEntered=true、logged=null；释放 handler 后
  loggedOriginal=true。主代理核对 ScopedCallable.finally 发布顺序后保留。
  修复为 handler 设置记录后 countDown，断言前等待 diagnosed；实际效果只消除测试时序假设，
  不延长业务清理到日志 handler 完成。
- R6（P3）：参考页把 token 都描述为内部类型，但 CancellationToken 本身仍公开。
  对照 public class 与 API surface 测试后保留；改为“结果不暴露执行所属 token/运行 future”，
  实际效果是准确说明结果能力边界，保留独立 token API。

其他生产取消/清理/泛型/Java 8 路径未发现确定缺陷。R4 的额外取消响应日志为明确取舍。
最终 focused review：同一 gpt-6-astra，独立预算上限 8,000 tokens，只读、未运行 Maven。
核对补测的闩锁/线程 join、异常 identity、typed combine、取消归因，以及三处临时恢复代码。
未发现新确定缺陷；告警先于调用线程结果发布，join 建立可见性，日志测试无需额外轮询。

分类复核：同一 gpt-6-astra，独立预算上限 5,000 tokens，只读、未运行 Maven。
首轮全部 78 个编号恰好分类一次，无遗漏/重复/额外编号；E 依据获确认，
包含委托边界检查允许文案不同的限定。重跑后的 114 归类纠正与新增 adapter 缺口见 §6.2。

### 7.1 Kimi 评审循环

用户指定的额外评审使用已安装 Kimi CLI 2.1.1。该版本拒绝 --auto 与 -p 同用
（错误为 Cannot combine --prompt with --auto），因此采用受支持的非交互 kimi -p。
只读独立预算上限 10,000 tokens，先读 R1–R6 基线；跳过 queue、explore 和原有未提交改动，
禁止改文件、Maven、commit/push 与再委派。会话保留为
session_0520c8b2-a6c9-4601-800a-62a3b8093f35；prompt/完整日志保留在
target/kimi-synchronous-review-r1-prompt.md、target/kimi-synchronous-review-r1.log。

K1（P3，保留；评审 D1）：删除 Par.submit 文案后，TaskOptions Javadoc 残留
“combine, — and fan-out” 断句。主代理对照当前文件确认；改成完整的名称来源、单任务并行度
与 group/batch 配置句子。实际效果仅修正文案，签名和执行逻辑不变，不新增运行测试。

第一轮未确认其他生产/并发缺陷。fromTask 的成功 future/非成功 outcome 假设被驳回：
终态 future 的成功读取决定 SUCCESS，结果冻结前已经完成，不会仅因稍后的 token 取消变成失败。
评审提出的 pool starvation 保留为执行布局限制；不能由此推导有限 deadline 下结果等待无限：
独立 timer 和 bind-before-submit 仍能取消排队 future。不合作 body/execute 的墙钟耗时限制
已经明确记录。G/D 存活项仍是覆盖限制，不当作已复现的生产缺陷。

第二轮 Kimi：独立只读预算上限 3,000 tokens，确认 K1 修复和各 {@link} 的真实签名，
没有新确定缺陷；也确认有限 deadline 的 starvation 处置与严格 PIT 算术。
会话 session_f942ece6-69f4-4455-8765-174a3cc0b7e7，prompt/日志保留为
target/kimi-synchronous-review-r2-prompt.md、target/kimi-synchronous-review-r2.log。

K2（P3，主代理补充，保留）：asFuture 文案把所有取消标签都描述为 CancellationException
cause，但公开 failed 工厂可接受任意 Throwable。现有 everyLeftKeepsOriginalThrowableIncludingCancellationAndError
测试已经逐个 outcome 与三类 Throwable 交叉验证此事实。修复为“给定异常不转换”，
并把指南/契约的 CancellationException 承诺限定为库执行产生的取消。实际效果仅使文档
匹配既有 Either 语义，不改变构造、读取、异常 identity 或 Future 状态，不新增重复测试。

第三轮 Kimi：独立只读预算上限 2,500 tokens，确认 K2 与现有交叉测试、K1 和全部修复
均准确且仅改文档，检查范围内没有剩余确定缺陷。会话
session_0b196905-2252-4458-97f9-dec11903ac41；prompt/日志保留为
target/kimi-synchronous-review-r3-prompt.md、target/kimi-synchronous-review-r3.log。
其“v0.3 migration 是不可改的 dated record”备注被驳回：v0.3 是当前未发布迁移指南，
不是不可变 ADR 或已发布的 dated table；因此两种语言的迁移页也同步限定取消异常类型承诺，
并明确成功 adapter 正常返回值。K2 不改变既有工厂允许的 outcome/Throwable 配对。

第四轮 Kimi：独立只读预算上限 2,000 tokens，核对迁移页修复与工厂/适配实现，
未确认新缺陷。会话 session_6c9d59c3-03b4-4086-8b61-a823427a8d87；prompt/日志保留为
target/kimi-synchronous-review-r4-prompt.md、target/kimi-synchronous-review-r4.log。
本轮虽提及 recorded 非 null 分支，未追到下面的真实交错；不能将“未确认”当作无竞态证明。

K3（P2，主代理补充，保留）：group token 已提交取消、成员 token 尚未收到传播时，
成员抛 InterruptedException 并完成 future。TaskGroup 正确归为取消，但 Task.failure()
仍按成员 token 返回原异常。fromTask 的 recorded 快路径跳过取消包装，导致冻结结果
带取消 outcome，却直接以 InterruptedException 为 Left，不满足库执行取消的异常契约。
修复为 recorded 与 ExecutionException 两条读取路径复用权威 outcome 的归一化：
USER_FAILURE/SUBMISSION_FAILURE 与已有 CancellationException 保留原对象；其余取消
包装为带任务名/归因的 LeanCancellationException，原异常为 cause。公开 failed 工厂不变。

新增三态参数化闩锁测试，分别暂停 token commit 后的传播、组完成后的 aggregate callbacks，
模拟合法线程抢占，使成员的本地 USER_FAILURE/原异常在冻结前仍可读。初版 fail-fast 测试
误把无失败触发成员的组 outcome 也预期为 FAIL_FAST；对照 deriveOutcome 后纠正为
组 MEMBER_CANCELLED、成员 FAIL_FAST。纠正后只撤回 recorded 分支修复，三态均在
CancellationException 类型断言失败；恢复修复后 8 项定向测试通过。
日志：target/recorded-cancellation-fixed.log、target/reverse-recorded-cancellation.log。

第五轮 Kimi：新会话独立只读预算上限 6,000 tokens，先读已处置项，再审 K3 的真实交错、
冻结、异常 identity、测试 gate/cleanup、Java 8 与泛型/null 契约；未发现新确定缺陷。
会话 session_51d9d558-cc83-41d2-abb6-804d17568410；prompt/完整日志为
target/kimi-synchronous-review-r5-prompt.md、target/kimi-synchronous-review-r5.log。
主代理逐项核对其非 finding 备注：

- SUCCESS 与 recorded failure 同时存在：降级为内部矛盾输入的异常形状差异；合法路径中
  SUCCESS 要求 future 成功，submission failure 的 phase claim 排斥 body 执行，因此不可达。
- “published 之前已经冻结，第二个 gate 多余”：驳回。converge 之前构造的是内部
  TaskGroupReport；ImmediateResult 在测试 published 之后调用的 finish 中冻结。
  gate 保留后续 callbacks 的暂停点，不据错误的冻结顺序移除它。
- “original cause” 可能误指原 body 异常：不列为缺陷；指南此处指存储 Throwable，
  valueOrThrow/asFuture 以该对象为 ExecutionException.cause。取消包装的原 body 异常
  位于下一层 cause，K3 回归明确验证两层 identity，不改变公开 Either 工厂语义。
