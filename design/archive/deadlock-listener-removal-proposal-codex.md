# 删除 `DeadlockDetectionListener`：图检测结果 future 终稿

> 状态：已实现（2026-09-27）
>
> 综合 Codex 与 ccyolo 独立草案。用户明确选择保留 policy 开关；其余细节按本文收敛。
> 本轮仅编写终稿，不实施代码；后续实现交给 Kimi。

## 1. 方案摘要

删除公开 `DeadlockDetectionListener` 及 policy 中的 listener 注册/读取面，把检测结果归宿到
`TaskGraphObservationScope`。保留 `ParRuntimeDeadlockPolicy.enabled`：它仍然是“是否在 Scope
关闭时执行检测”的独立开关，和“是否打开 Scope 记录图”分离。

Scope 新增：

```java
ListenableFuture<TaskGraphReport> reportFuture();
```

新增顶层不可变 `TaskGraphReport` 作为结果数据类型。内部由 `SettableFuture` 支撑，调用方
只能读取、等待或 `Futures.addCallback`，不能 set/cancel 检测。每个 Scope 在 `close()` 的
图快照上发布一次结果；检测禁用、无 issue、有 issue 都以成功结果发布，检测异常则 future
失败并通过 JUL 记录。

结果保留四个现有 flag 和两组边文本，并增加 `status()` 区分 `DISABLED`、`NO_ISSUE`、
`ISSUE`。检测异常不伪装成正常报告，而是以 future failure 表达。结果只反映 Scope 关闭时
冻结的图，不等待业务任务，也不改变图记录、TTL、取消和执行器语义。

## 2. 代码事实

- `ParRuntimeDeadlockPolicy` 现在保存 `enabled` 与 listener 列表；Builder listener 按身份去重，
  `listeners()` 返回不可变列表。终稿删除 listener 列表，但保留 `enabled()` 与
  `Builder.enabled(boolean)`。
- `TaskGraphObservationScope.close()` 用 `AtomicBoolean` 保证检测只跑一次；检测在
  `TaskGraphData.snapshot()` 上构造一个事件，仅在任一 cycle/self-loop 为真时调用 listener。
- `TaskGraphData` 的派生图、flag、渲染文本来自同一不可变 Snapshot；新 edge 会使缓存失效。
- 关闭 finally 恢复当前 TTL 的外层 Scope；`close()` 不等待任务 body。关闭标志阻止新记录
  获取 Scope，但已获取图引用的写入仍可能与快照竞争；结果边界见 §3.3。
- 检测 listener 在关闭线程同步运行；异常隔离和 JUL warning 是框架当前额外责任。

## 3. API 与生命周期

### 3.1 删除与新增

删除：

- `DeadlockDetectionListener` 接口；
- `ParRuntimeDeadlockPolicy.Builder.listener(...)`；
- `ParRuntimeDeadlockPolicy.listeners()` 与身份去重实现；
- `TaskGraphObservationScope` 的 listener 循环。

新增顶层不可变 `TaskGraphReport`，访问器为：

```java
Status status();
boolean taskCycle();
boolean selfLoop();
boolean executorCycle();
boolean executorSelfLoop();
boolean anyIssue();
String taskEdges();
String executorEdges();
```

其中 `Status` 只有 `DISABLED`、`NO_ISSUE`、`ISSUE`。`DISABLED` 表示 policy 未启用检测；
`NO_ISSUE` 表示已从冻结快照完成检测且没有环/自环；`ISSUE` 表示至少一个检测 flag 为真。
检测执行异常不构造 `TaskGraphReport`，由 `reportFuture()` 以 failure 终结。旧的
`DeadlockDetectionListener.DeadlockDetectionEvent` 不保留兼容别名。

`TaskGraphReport.Status` 为嵌套公开 enum；报告字段均 final，文本非 null。
`anyIssue()` 是四个 flag 的或，必须与 `status() == ISSUE` 等价。生产结果只由库内部工厂
创建，不新增公开构造器；用户测试可从真实 Scope 获得报告，或在应用层建立自己的告警输入。
`toString()` 保留当前事件的日志字段格式，便于继续使用现有日志解析。

以下配置面完整保留：`ParRuntimeDeadlockPolicy`、其 Builder 与 `enabled()`、
`Builder.enabled(boolean)`、`ParRuntime.deadlockPolicy()`、
`ParRuntime.Builder.deadlockPolicy(...)`。默认 enabled 仍为 false。disabled 不阻止图记录
和现有静态查询，也不产生阳性告警；它只跳过关闭时的检测与渲染。

### 3.2 `reportFuture()` 契约

每个 Scope 构造一个 `SettableFuture<TaskGraphReport>`，对外返回同一个不可取消视图。
future 状态：

| Scope 状态 | future |
|---|---|
| 尚未 close | pending |
| close + policy disabled | 成功，`status() == DISABLED`，flag 全 false，边文本为空 |
| close + enabled + 无 issue | 成功，`status() == NO_ISSUE`，flag 全 false，边文本为空 |
| close + enabled + issue | 成功，`status() == ISSUE`，携带 flags 与边文本 |
| snapshot/render 失败 | failure，cause 为原异常，JUL warning |

Scope 的 close 完成屏障是 future 已 set；并发 close 调用不得在 CAS 失败后提前返回。首次
close 线程负责冻结并计算，竞争者等待同一内部 completion signal。future 的 callback 不在
图数据锁内执行；direct executor 可能在 close 返回前运行，但 callback 看到的当前 Scope
已经恢复。

future cancel 必须返回 false，不取消业务工作、不修改 Scope closed 状态。检测结果 set 后
不可更改；关闭幂等且不重复计算。复用 TaskListener 删除变更中的 package-private
`TaskObservation.readOnly(...)`；如果该实现尚未可用，复用同等的私有只读包装。
不得直接暴露 sink，也不得用可取消自身视图的 `Futures.nonCancellationPropagating` 替代。

### 3.3 完成与图边界

这里的 Scope 完成是请求级**图观测**关闭，不是业务任务 body 退出、batch join 或 Runtime
quiescence。任何正常返回的 `close()` MUST 保证 `reportFuture().isDone()` 为 true，且可用
`Futures.getDone` 得到报告或检测异常。`closed()` 保持现有“关闭已开始”的含义，不能单凭
该标志判断报告已发布。

关闭顺序：CAS 抢关闭权 → 启用时读取一份 `TaskGraphData.snapshot()` 并计算报告 → 恢复
当前线程的 Scope 绑定 → set 报告或异常。flag、任务边文本与 executor 边文本必须来自
同一份 snapshot；完成 future 时不持图锁，不重新读取动态图。disabled 跳过 snapshot，
直接准备 `DISABLED` 报告。

不新增 `TaskGraphData.freezeAndSnapshot()` 或 frozen 状态。关闭标志只阻止新调用取得
Scope；已经取得 data 引用的记录与 snapshot 共用同一把锁：在快照线性化点之前记录完成
的边必须进入结果，之后记录的边不进入这份不可变结果。不宣称 CAS 能精确截断所有在途
写入，也不宣称关闭后的图内存绝不会再变化。结果不会被晚到边改写；内层关闭后的晚到
工作不自动归入外层 Scope。提前关闭得到的是部分请求图，此次不改变其归属机制。

并发 `close()` 的 CAS 败者在锁外等待内部 sink 终态，保留中断标志；报告失败也不得让
等待者留在 pending。每次 `close()` 都在自己的 finally 做上下文恢复，不能因 CAS 失败
跳过恢复。不得把 opening thread 的 previousScope 装入另一个线程；打开线程按栈恢复
自己的 previousScope，worker 的上下文恢复由 TTL replay/restore 负责。嵌套 Scope 以
栈顺序关闭，不新增对乱序关闭的支持。

direct executor callback 可在胜者 `close()` 返回前运行，但运行时值已经固定、该线程
的 Scope 已恢复。callback 中再次 `close()` 或 `get()` 必须无自锁；竞争者等待的是 sink
终态，不是 callback 执行结束。callback 的线程上下文不承诺专门清空 current task。

### 3.4 诊断异常与日志

普通检测异常：先准备失败 cause、恢复上下文，再 `setException(cause)`；保留现有检测
WARNING，`close()` 不因该诊断异常向外抛出，避免掩盖业务异常。`Error` 同样先尽力完成
future 并恢复上下文，再原样重抛；平台无法继续执行时不作可用性保证。

阳性报告保留现有 JUL WARNING；阴性与 disabled 不输出问题告警。日志失败不能跳过报告
发布。用户 callback 的排队、阻塞和异常处理交由其 executor/Guava 语义负责；选择 direct
executor 仍可能使 callback 拖慢关闭线程，不宣称换成 future 就消除了这类延迟。

## 4. 改前与改后

改前配置全局 listener，且没有事件时无法区分三种状态：

```java
AtomicReference<DeadlockDetectionListener.DeadlockDetectionEvent> event = new AtomicReference<>();
ParRuntime runtime = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                .enabled(true).listener(event::set).build())
        .build();
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    handleRequest();
}
// event == null：无 issue、未启用、检测异常都可能如此
```

改后检测结果与请求 Scope 绑定：

```java
ParRuntime runtime = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .build();
TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
Futures.addCallback(scope.reportFuture(), new FutureCallback<TaskGraphReport>() {
    @Override public void onSuccess(TaskGraphReport report) {
        if (report.status() == TaskGraphReport.Status.ISSUE) {
            alert(report.taskEdges(), report.executorEdges());
        }
    }
    @Override public void onFailure(Throwable failure) { reportDiagnosticFailure(failure); }
}, diagnosticExecutor);
try (TaskGraphObservationScope ignored = scope) {
    handleRequest();
}
```

同步消费同样成立：保存 `scope.reportFuture()`，在 try-with-resources 退出后调用
`Futures.getDone(report)`。关闭前 `get()` 可能一直等待，不能在关闭该 Scope 的线程中先
等待结果再执行关闭。关闭后再注册 callback 也不会丢失报告。

## 5. 消除的失败与失去的能力

消除：全局 policy listener 与请求 Scope 的隐式关联；框架自行选择同步投递线程和遍历
用户 listener 的责任面；无回调时三态不可区分；并发 close 返回但诊断尚未发布的竞态。

失去：一次 policy listener 注册、覆盖整个 runtime 所有请求的全局推送；只有 issue 才产生回调的
事件过滤由用户通过 `anyIssue()` 或 `status()` 完成；框架统一隔离 listener 异常的责任
移交给 Guava callback executor。旧 Event 的公开构造器也随删除而消失。报告仍只含 flag
和格式化文本，不公开内部图；阴性报告不提供边文本。检测仍是潜在图结构诊断，不证明
线程已经死锁，不能从无问题推断 executor 的不可看透部分安全。

## 6. 实现与迁移

`TaskGraphObservationScope` 负责 SettableFuture、快照计算、异常完成、并发 close 等；
`ParRuntimeDeadlockPolicy` 删除 listener 列表但保留 enabled；新增 `TaskGraphReport`，移除
`DeadlockDetectionEvent` 的 listener 嵌套。
更新 TaskGraph 相关测试、公开 API 表面、Javadocs、中文/英文用户文档、demo 和 v0.x
迁移说明。旧 `listener(...)` 调用改为保存 Scope 的 `reportFuture()`，使用 `get` 或
`Futures.addCallback`；旧嵌套类型导入改为 `TaskGraphReport`，`hasTaskCycle()` 等改为
`taskCycle()` 等裸访问器。现有 Scope 静态 `hasTaskCycle()` 等方法保留不变。

具体实施范围：

- 删除 SPI 文件及嵌套 Event，新增 `TaskGraphReport` 和 `Status`；
- 收窄 `ParRuntimeDeadlockPolicy`，保留 enabled、注册/读取面和默认值；
- 修改 Scope 的报告计算、发布与重复/并发关闭流程，复用现有图锁与快照机制；
- 迁移 `DeadlockDetectionListenerTest`、TaskGraph close/极性/快照/导出测试、ParRuntime
  policy 测试与 `PublicApiSurfaceTest`；保留所有与本变更无关的 purge/执行器断言；
- 更新中英文 user-guide、当前 v0.3 迁移说明、CHANGELOG、观测契约 §11 及
  `executor-transparency.md` 的 I5。历史 ADR 不改写；其他旧迁移文档保留历史含义；
- 更新 demo 使用真正的 observation Scope 和 report future，替换过时 listener 宣传。

先完成并验证已有 TaskListener 删除变更，再由 Kimi 基于其最终代码实现此方案，避免并行
修改 `ParRuntime`、公共 API 表面和同一组测试。只暂存本变更文件，遵守根 AGENTS 的验证
与 Git 工作流；本轮写终稿不执行这些实现操作。

必须测试：禁用/阴性/阳性/异常、单线程与并发 close、重复 close、late edge、snapshot 单一
版本、TTL 外层 Scope 恢复、direct callback、future cancel 不传播，以及关闭返回后 future
必定终态。

还必须验证：disabled 时静态查询仍工作且不打问题告警；阴性与 disabled 可区分；检测
异常成功恢复上下文且 future 失败；callback 阻塞时其他 close 仍能看到已终态的数据；
close 后再注册 callback、不同 Runtime/嵌套 Scope 隔离、日志失败不阻断发布、中断标志
保持、Error 的尽力发布与重抛。实现中不得为覆盖测试暴露新的公开诊断 SPI。

## 7. 设计判据与终稿裁定

按 `first-principles.md`：用既有 LF/SettableFuture 交付结果，删除全局 listener 机制，不新增
执行管道或 ThreadLocal；结果仍是只读图诊断，不改变取消/deadline/任务结果；归宿是图
观测 Scope，不撑大 Batch/Group。失去“一次注册保证所有请求被消费”的便利性已在 §5
明示，保留 policy 与 JUL 告警兜底。

终稿裁定：保留 policy；使用 `TaskGraphReport + Status + reportFuture()`；正常分支数据化、
检测异常让 future 失败；并发关闭返回时数据必可得；复用单快照，不引入新的图冻结机制；
不维护未关闭 Scope 注册表，也不让 `ParRuntime.close()` 代替用户关闭 observation Scope。
实现按本文执行，独立草案仅作为讨论记录。
