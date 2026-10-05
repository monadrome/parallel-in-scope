# 观测 close 的日志重入自等待修复（F1）

对应 2026-10-04 源码审计的 F1 发现。行为契约见
[task-group-observability-and-verification.md](task-group-observability-and-verification.md)
「发布与诊断的顺序」。

## 触发

`TaskGraphObservationScope.close()` 先置 `closed`，再运行检测。旧实现中 `detect()` 在发现
ISSUE 时同步调用 JUL（`logIssueQuietly`），此时 `reportSink` 尚未发布。用户可替换的 JUL
`Handler.publish` 若重入同一 scope 的 `close()`，重入调用走 loser 路径等待 `reportSink`；
而第一层 close 正等待 handler 返回——同一线程等待自己，永久阻塞。

复现（探针 `SourceAuditProbe reentrantlog`，修复前）：

```text
reentrant close blocked=true
report done=false
awaitReportPublication -> close -> logIssueQuietly -> detect -> publishReport -> close
```

handler 没有抛异常，因此既有的「吞掉日志 handler 异常」保护覆盖不到这条路径；既有测试
只覆盖抛错的 handler，没有覆盖重入。

## 改前 / 改后的用户代码

用户代码不变——这是一个活性缺陷，触发条件是配置了会重入的日志 handler：

```java
Logger log = Logger.getLogger(TaskGraphObservationScope.class.getName());
log.addHandler(new Handler() {
    @Override public void publish(LogRecord record) { scope.close(); } // 例如聚合 flush 时误调
    ...
});
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    work(); // 图中出现环
} // 改前：close 永久阻塞，reportFuture 永远 pending；改后：close 正常返回，报告已发布
```

## 被消除的失败模式

关闭线程在检测出 ISSUE 时通过用户日志 handler 重入 `close()`，形成单线程自等待：
close 不返回、`reportFuture()` 永远 pending、线程栈闭环。

## 实际修复

`TaskGraphObservationScope`：

- `detect()` 改为纯计算：只做快照与判定，不调用 JUL 等外部诊断代码；
- `publishReport()` 的顺序固定为：计算报告 → 恢复调用线程的外层 scope 绑定 →
  `reportSink.set(report)` 发布 → 仅当报告含 ISSUE 时在 `finally` 中调用
  `logIssueQuietly`。发布完成后，handler 的重入 `close()` 走 loser 路径时 sink 已终态，
  立即返回；
- 报告 listener 的失败隔离遵循 Guava `AbstractFuture`/`ExecutionList` 语义：listener 的
  普通 `Exception` 被 Guava 捕获并记录；listener 的 `Error` 会从 `set` 逃逸（Guava 在调用
  listener 之前已用 CAS 提交报告值并释放等待者），因此它可能逃逸 `close()`，但不可能让
  报告保持 pending。诊断日志放在发布的 `finally` 里，listener 的 `Error` 不会跳过它；
  阻塞的 direct listener 只能推迟诊断与 winner 的返回，不能阻止报告到达终态；
- 诊断 handler 自身抛出的异常或 `Error` 由 `logIssueQuietly`/`logDetectionFailureQuietly`
  吸收，不影响已发布的报告；
- 检测失败路径（`setException` 先于诊断日志）此前已是正确顺序，未改动。

## 有意的顺序变化

ISSUE 的 WARNING 诊断日志从「发布之前」移到「发布之后」：`reportFuture()` 的
directExecutor 回调现在可能先于该日志行执行。报告内容、发布一次性、`close()` 返回时
future 必已终态等语义均不变。该顺序已写入契约与 `close()` Javadoc。

## 回归覆盖

`TaskGraphReportFutureTest` 新增三个用例：

- `reentrantDiagnosticHandlerSeesThePublishedIssueReportAndDoesNotSelfWait`：handler 在
  ISSUE 报告的诊断日志中重入同一 scope 的 `close()`；断言 handler 内看到已恢复的外层
  scope（恢复先于诊断）、`reportFuture()` 已完成且能读到 ISSUE 报告（发布先于诊断）；
  断言重入后再次 close 仍返回同一报告实例；close 在携带绑定栈的 daemon 线程上执行并以
  10 秒上界等待，回归时测试失败而非挂起整个测试 JVM。
- `waitingCloseBlocksUntilPublicationAndSeesDoneReport`：loser close 在 winner 检测中
  不得提前返回（有 started 握手），返回时报告必已终态。
- `issueReportAndDiagnosticSurviveAnErrorThrowingDirectListener`：direct listener 抛
  `AssertionError` 时，Error 逃逸 `close()`，但报告已终态且 ISSUE 诊断仍从 `finally`
  发出。

既有用例继续覆盖：抛异常 / 抛 `Error` 的 handler 下的发布保证、检测失败路径、并发与
重复 close、direct 回调重入、等待方中断标志保持。

## 验证记录

- 探针复现：修复前 `reentrant close blocked=true, report done=false`；
  修复后 `blocked=false, report done=true`。
- 定向测试：`mvn -o test -Dtest='TaskGraphReportFutureTest'`——21 个用例全绿。
- 反向验证（每次只撤销修复、保留新测试）：
  - 把 `logIssueQuietly` 移回 `detect()` 内部：重入用例如期失败（close 等待 10 秒上界
    超时），测试 JVM 不挂起；
  - 保留发布后日志但去掉 `finally`：listener-Error 用例如期失败（WARNING 被 Error 跳过）；
  - 恢复修复后全部全绿。
- 全量：`mvn -o test`——774 个用例全绿（日志见任务目录）。
- 外部消费方：仅用公开 API 的 Java 8（Corretto 8.412）探针在独立包中编译运行，重入
  handler 场景 `blocked=false`、报告 ISSUE、handler 内可见已发布报告。

## 对抗性评审发现与处置

评审：Codex（GPT-6.1-Sol xhigh），cmux workspace:30，只读，报告存于任务目录
`review-codex.md`。结论 APPROVE-WITH-FIXES，无 BLOCKER/MAJOR。

1. **MINOR，保留并修复**：设计记录原称「listener 异常由 Guava `ExecutionList` 吸收」不
   准确——Guava 捕获 listener 的 `Exception`，但 `Error` 会从 `set` 逃逸；日志移到发布
   之后使该 Error 新增「跳过 ISSUE 诊断」的副作用（报告本身已提交，不重演 F1）。处置：
   诊断日志改入发布的 `finally`；修正设计记录措辞；新增
   `issueReportAndDiagnosticSurviveAnErrorThrowingDirectListener`；契约补写 listener
   Error 条款。反向验证通过。
2. **MINOR，保留并修复**：原重入用例的恢复断言 `current() == null` 被 `current()` 的
   `closed()` 守卫掩盖，不能发现「漏恢复」。处置：用例外层套活跃 outer scope，在 handler
   内断言 `current() == outer`（恢复先于诊断），并断言重入/重复 close 后测试线程绑定不
   变。
3. **NIT，保留并修复**：loser 等待用例把 200ms 超时当作等待证据，存在调度假绿窗口。
   处置：contender 增加 started 握手，并在 close 返回后立即断言报告已终态。

评审核实的既有结论（无需改动）：重入/并发 close 的屏障是 sink 终态而非回调完成；诊断
handler 的异常/`Error` 不影响已发布报告；无现存测试或文档要求 WARNING 先于回调；主源码
无 Java 8 API、泛型或 nullness 问题。

## 变异覆盖（PIT）

命令：`mvn -o -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage`，
targetClasses=TaskGraphObservationScope，targetTests 含 TaskGraphReportFutureTest 及
TaskGraphSnapshotConsistencyTest、TaskGraphBatchIdentityTest、TaskGraphPolarityTest、
TaskGraphReportTest、TaskGraphScopeOwnershipTest，mutators 含 VOID_METHOD_CALLS。
（首轮窄 scope 运行暴露的 flag 组合与渲染文本幸存者均被上述既有测试类杀死；同时新增了
loser 等待用例杀死 `awaitReportPublication` 删除变异。）

- TIMED_OUT（line 240，negate winner/loser 条件）：变异制造自锁等待，等价于已杀死。
- NO_COVERAGE（line 121 `install` 的 set；line 278 `addSuppressed`）：worker 绑定入口与
  「发布失败叠加 Error」路径，目标测试类之外；本次变更未触碰。
- SURVIVED（均为本次未触碰的既有行，已逐条分类）：
  - 三处 `restoreCurrentScope` 删除（loser 路径、检测失败路径、Error 路径）与
    `restoreCurrentScope` 内 `CURRENT.remove()` 删除：`current()` 的 `closed()` 守卫使
    公开观测无差异，残余差异是已关闭 scope 在 ThreadLocal 上的滞留——属 F2（跨线程
    close 与滞留链）的处理范围，本任务不改变该语义；
  - `restore(null)` 的 `CURRENT.remove()`/`set` 删除与取反：测试清理路径，公开行为等价
    （ThreadLocal 项滞留差异）；
  - `logDetectionFailureQuietly` 删除：纯诊断日志副作用，契约即 best-effort，无断言
    JUL 输出的测试——接受的幸存者。
