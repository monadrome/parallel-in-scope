# v0.3 迁移指南

`0.3.0` 保持 `0.2.0` 的 API 形态，但改变了若干运行期契约。对多数调用方是源码兼容迁移，只有少量签名变化。

本版本的主题是：库不再在未声明的场合替你做决定——关闭作用域会等待而非只发取消，quiescence 指任务体退出而非 future 完成，checkpoint 守卫失败而非跳过，executor 拒绝后不再在你没选择的线程上运行你的代码。

## executor 拒绝后默认不再执行用户代码

`TaskType.CPU_BOUND` 过去隐含一条调度规则：绑定的执行器拒绝任务时，任务在提交线程上执行。而 `CPU_BOUND` 同时是默认任务类型，因此每个未声明类型的任务在拒绝时都会静默地在调用方线程上运行用户代码。

该回退现在是显式选项，且默认关闭：

```java
// 0.2.x：拒绝后会在提交线程执行——CPU_BOUND 隐含 inline
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：同样的选项改为以 SUBMISSION_FAILURE 失败
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：显式恢复旧行为
BatchOptions.timeout("load", Duration.ofSeconds(5))
        .taskType(TaskType.CPU_BOUND)
        .runOnCallerThread(true);
```

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskOptions` / `BatchOptions` 公开面 | `taskType`、`rejectEnqueue` | `taskType`、`rejectEnqueue`、`runOnCallerThread` |
| `CPU_BOUND` 任务被拒绝 | 在提交线程执行 | 以 `SUBMISSION_FAILURE` 失败，任务体不进入 |
| `IO_BOUND` / `MIXED` 任务被拒绝 | 以 `SUBMISSION_FAILURE` 失败 | 不变 |
| 声明 `runOnCallerThread(true)` 后被拒绝 | — | 在提交线程执行 |

`runOnCallerThread` 对任意执行器、任意任务类型都生效。请有意地启用它：任务体此后在提交线程上运行，这是背压而非排队，并且会阻塞该线程当时在做的事——对批次而言，就是驱动提交窗口的那个线程。

`TaskType` 不再影响拒绝路径。它现在只驱动一件事：`SmartBlockingQueue` 是否拒绝入队（`CPU_BOUND` 即使 `rejectEnqueue(false)` 也拒绝；其他值可入队）。在任何其他队列上，`TaskType` 不改变任何行为，且 `IO_BOUND` 与 `MIXED` 不可区分——`MIXED` 保留为对意图的声明，不是调度指令。

终端 combine 不读 `runOnCallerThread`：它没有 caller thread，由框架在 join 时提交。被拒绝的 combine 仍以 `SUBMISSION_FAILURE` 失败。

## 关闭作用域现在会等待

`TaskGroup.close()` 与 `TaskBatchResult.close()` 的语义是"取消 + 有界等待"：先取消未完成成员，再在作用域的 close grace 内等待任务体退出。未配置 grace 时，等待预算派生自关闭时该作用域的剩余执行 deadline。

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | 取消未完成成员 | 取消后，在 close grace 内等待 |
| `TaskBatchResult` | 不是 `AutoCloseable` | `AutoCloseable`，语义相同 |
| grace 配置 | — | `TaskGroupOptions.closeGrace(Duration)` / `BatchOptions.closeGrace(Duration)` |
| 只发出取消请求 | `close()` | `cancel()`（组）；`closeGrace(Duration.ZERO)` 也使 `close()` 只取消 |

`close()` 正常返回仍不证明任务体已退出：忽略中断的任务体可以活过 grace。释放任务体使用的资源前，用 `awaitBodyCompletion(Duration)` 确认——它只在每个任务体都已退出或已被原子判定不会启动时返回 `true`。

## quiescence 指任务体退出

`GlobalPar.awaitQuiescence(Duration)` 现在等待任务体退出，而不只是 future 排空。运行中被取消的任务会立刻完成 future，但可能仍在执行用户代码，quiescence 两者都算。

## checkpoint 守卫改为失败而非跳过

`Checkpoints.checkpoint(String, boolean)` 不再 fail-open。名称与当前 scoped task 不匹配——或在任何 scoped task 之外调用——会抛 `IllegalStateException`，而不是静默跳过取消检查。无参的 `Checkpoints.checkpoint()` 是主要形式、无需名称；原名称未携带信息时直接去掉该参数即可。

## 收窄的签名与形态

| 变化 | 迁移 |
|---|---|
| `TaskGroup.future(TaskKey)` 与 `CompletedTaskValues.value(TaskKey)` 改用 `TypeToken.isSupertypeOf` 比较完整泛型 | 声明 `List<Integer>` 的 key 不再解析注册为 `List<String>` 的成员。把 key 的类型参数与注册类型对齐。 |
| `CancellationToken` 改为 `final`，`bind(...)` 改为包私有 | 移除子类与外部 `bind` 调用；token 承载库的归因真相。 |
| `Task` 改为包私有，公开契约只有 `TaskFuture` | 原先使用 `Task` 的位置改声明 `TaskFuture`。 |
| `Par.map` 接收任意 `Collection`，不再只收 `List` | 源码兼容；非 `List` 输入在入口处快照。 |
| `TaskBatchResult.BatchReport.stateCounts()` 不再 `@Nullable`，`BatchReport` 构造器改为包私有 | 移除对 `stateCounts()` 的判空；report 一律从库获取。 |
| `GlobalPar.installGlobal` 与实例 `close()` 对称 | 对已安装实例调用 `close()` 会释放全局槽位，重启的上下文可以再次安装。 |
