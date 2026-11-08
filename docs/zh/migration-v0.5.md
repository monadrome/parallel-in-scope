# 迁移到 v0.5

本版本把队列公开类型改成直接描述可观察契约的名称。这是源码和二进制不兼容的改名，
不保留旧类型兼容别名。

| 删除的名字 | 替换为 |
|---|---|
| `io.github.monadrome.parallelinscope.SmartBlockingQueue` | `io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue` |
| `io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue` | `io.github.monadrome.parallelinscope.queue.ResizableBlockingQueue` |

改前：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SmartBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

改后：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SubmissionAwareBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

`rejectEnqueue`、`capacity()` 和 `setCapacity(int)` 的行为保持不变。`TaskType` 仍然只用于
诊断，不改变入队准入；只有 `rejectEnqueue(true)` 会请求
`SubmissionAwareBlockingQueue` 拒绝入队。

`DrainingBlockingQueue` 不变。消费方需使用 `0.5.0` 重新编译，并更新 import 和类型引用。
