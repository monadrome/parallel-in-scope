# 协作式取消（Cooperative Cancellation）


## 为什么需要"协作式"取消？

Java 的 `Thread.interrupt()` 只能中断**阻塞操作**（如 `Thread.sleep()`、`Object.wait()`、`BlockingQueue.take()` 等）。对于正在执行纯计算的 CPU 密集型代码，interrupt 信号不会产生任何效果——线程会一直运行直到计算结束。

parallel-in-scope 采用**协作式取消**模型：框架负责发出取消信号（通过 `CancellationToken` 的状态变更），但**任务代码需要主动检查这个信号**。这个检查动作称为 **checkpoint**。

> 类比：协作式取消就像公路上的收费站——车辆（任务）在经过收费站（checkpoint）时才会被拦截。如果一段公路上没有收费站，车辆就会一路畅行不受控制。

## 框架自动做了什么

parallel-in-scope 在以下位置**自动插入**了 checkpoint 和取消响应：

| 位置 | 机制 | 你需要做什么 |
|---|---|---|
| 任务执行前 | `ScopedCallable` 在调用你的代码之前自动执行一次 `Checkpoints.checkpoint()` | 无需任何操作 |
| I/O 阻塞期间 | `futureToken.cancel(true)` 发送 `Thread.interrupt()`，阻塞操作抛出 `InterruptedException` | 无需任何操作 |
| 滑动窗口提交循环 | `SlidingWindowSubmitter` 在每次提交前检查取消状态，发现取消后停止提交剩余任务 | 无需任何操作 |

## 滑动窗口准备与终态

每个元素在提交前就有完整的内部执行 future。取消和 deadline 绑定完成后才开始提交，
滑动窗口只决定 prepared task 何时进入线程池；没有 placeholder 或后续 delegate 绑定。
因此取消既能中断正在运行的任务，也能阻止尚未提交的任务开始。

`Par.map` 等待所有结果确定并尝试有界清理，返回冻结的 `ImmediateResult` 数据。
`unfinishedBodies()` 报告尚未确认退出的直接任务体；取消终态不代表任务体已经停止。

这意味着：
- **尚未开始的任务**会被自动跳过（预执行 checkpoint 拦截）。
- **正在阻塞等待 I/O 的任务**会被 interrupt 唤醒。
- **正在排队等待提交的任务**不会被提交。

## 你需要做什么：在 CPU 密集型代码中手动插入 Checkpoint

如果你的任务包含**纯计算循环**（没有 I/O 阻塞），框架无法自动中断它。你必须在合适的位置手动调用 checkpoint。

### 基本用法

```java
BatchOptions options = BatchOptions.timeout("my-task", Duration.ofSeconds(5)).parallelism(4);

global.par(ParId.of("myExecutor")).map(dataList, item -> {
    for (int i = 0; i < 1_000_000; i++) {
        // 每 1000 次迭代检查一次取消状态
        if (i % 1000 == 0) {
            Checkpoints.checkpoint();
        }
        heavyComputation(item, i);
    }
    return result;
}, options);
```

关键规则：
- **首选无参 `Checkpoints.checkpoint()`**：无条件检查当前 scope，不需要任务名，也不会因改名而失效。
- **带名字的 `checkpoint(taskName, lean)` 仍会校验当前任务名**：名字不匹配（笔误、重构后的旧名字、调用位置在任务外）会抛 `IllegalStateException`，而不是静默跳过安全检查。`lean` 为 `false` 时抛出带完整堆栈的标准 `CancellationException`，适合调试定位取消发生位置。

三种 checkpoint 在**任务作用域之外**的行为不同：

| Checkpoint | 在作用域之外 | 取消时 |
|---|---|---|
| `Checkpoints.checkpoint()` | 静默 no-op | 抛 `LeanCancellationException` |
| `Checkpoints.checkpoint(taskName, lean)` | 抛 `IllegalStateException` | `lean=true` 抛 `LeanCancellationException`；`lean=false` 抛带堆栈的 `CancellationException` |
| `Checkpoints.rawCheckpoint()` | 可用——不需要作用域，同时响应线程中断标志 | 抛 `LeanCancellationException` |

另有两个配套工具：

| 方法 | 用途 | 典型场景 |
|---|---|---|
| `Checkpoints.sleep(millis)` | 取消感知的 sleep，将 `InterruptedException` 统一转换为 `LeanCancellationException` | 替代 `Thread.sleep()` |
| `Checkpoints.propagateCancellation(ex)` | 在 catch 块中重新抛出取消异常 | 需要区分处理"取消"和"其他异常"时 |

## Checkpoint 插入策略

checkpoint 并非越多越好——每次调用都有微小开销（读取 `ThreadLocal` + 原子变量）。以下是推荐的插入策略：

**适合插入 checkpoint 的位置：**

```java
// 1. 长循环的每 N 次迭代
for (int i = 0; i < items.size(); i++) {
    if (i % 100 == 0) {
        Checkpoints.checkpoint();
    }
    process(items.get(i));
}

// 2. 多阶段计算的阶段之间
ResultA a = phaseOne(input);
Checkpoints.checkpoint();
ResultB b = phaseTwo(a);
Checkpoints.checkpoint();
ResultC c = phaseThree(b);

// 3. 递归调用的入口处
void traverse(TreeNode node) {
    Checkpoints.checkpoint();
    if (node == null) return;
    process(node);
    traverse(node.left);
    traverse(node.right);
}
```

**不需要插入 checkpoint 的位置：**
- I/O 操作附近（HTTP 调用、数据库查询等）——interrupt 已经能中断这些操作。
- 执行时间极短的函数——任务本身很快结束，取消没有意义。
- 框架自动 checkpoint 已覆盖的地方（任务执行前）。

## 在 catch 块中正确处理取消异常

当你的任务代码中有 try-catch 时，需要注意不要意外吞掉取消异常：

```java
global.par(ParId.of("myExecutor")).map(items, item -> {
    try {
        riskyOperation(item);
    } catch (Exception e) {
        // 错误做法：吞掉了所有异常，包括取消异常
        // log.error("failed", e);
        // return defaultValue;

        // 正确做法：先让取消异常透传，再处理其他异常
        Checkpoints.propagateCancellation(e);
        log.error("failed", e);
        return defaultValue;
    }
}, options);
```

`Checkpoints.propagateCancellation(e)` 会检查异常是否为 `CancellationException`，如果是则重新抛出；否则照常返回，让后续的异常处理逻辑继续执行——**除非当前作用域已被取消**，此时它会抛出 `LeanCancellationException` 而不是返回。

## 用 `Checkpoints.sleep()` 替代 `Thread.sleep()`

在 `Par` 任务内部，永远使用 `Checkpoints.sleep()` 替代 `Thread.sleep()`：

```java
// 不推荐
Thread.sleep(1000);  // InterruptedException 需要你自己处理

// 推荐
Checkpoints.sleep(1000);  // 自动将 InterruptedException 转换为 LeanCancellationException
```

`Checkpoints.sleep()` 将 `InterruptedException` 统一转换为 `LeanCancellationException`，使得中断驱动的取消和协作式取消在异常类型上保持一致，同时避免为正常取消采集无用堆栈。

## 取消的触发源

了解取消可能从何而来，有助于理解为什么需要 checkpoint：

| 触发源 | Token 状态 | 说明 |
|---|---|---|
| 兄弟任务失败 | `FAIL_FAST` | 同一批次中某个任务抛异常，其余任务被取消 |
| 超时 | `TIMEOUT` | 超过 `BatchOptions` 指定的超时时间 |
| 父作用域取消 | `PROPAGATED_CANCELLED` | 嵌套场景下，外层作用域取消，自动传播到内层 |

所有触发源通过内部 token 状态检查体现，checkpoint 只需判断是否应该停止。
任务体也可以抛出取消异常。`ImmediateResult.outcome()` 呈现冻结的任务归因，
见[即时结果](../user-guide.md#task-future-attribution)。
中断等待 `map` / `runAll` 的调用线程不会取消执行；等待结束恢复中断标志。
结果不暴露执行所属的 token 或运行中的 future，`asFuture().cancel(...)` 始终返回 false。

## 嵌套作用域的取消传播

当 `Par.map()` 内部再次调用 `Par.map()` 形成嵌套时，框架通过 `CancellationToken` 的父子链自动传播取消：

```
外层 Par.map(["A", "B", "C"])
  ├── A → 内层 Par.map([1, 2, 3])    ← 拥有子 CancellationToken
  ├── B → 抛出异常                     ← 触发外层 fail-fast
  └── C → 内层 Par.map([4, 5, 6])    ← 拥有子 CancellationToken
```

当 B 失败时：
1. 外层 token 转为 `FAIL_FAST`。
2. A 和 C 的子 token 通过父子链自动转为 `PROPAGATED_CANCELLED`。
3. A 和 C 的内层任务在下一次 checkpoint 或 I/O 阻塞时响应取消。

你不需要手动编排这个传播——前提是内层任务中有足够的 checkpoint。
