# Error Prone 告警清理（2026-10-12）

## 蒸馏摘要

- 核心出发点：逐项理解 Error Prone 告警，修正实际问题并为误报保留窄抑制，降低告警噪声。
- 不变量与拟议 MUST：清理 MUST 保留中断、取消、runner 交接和测试交错语义；
  MUST NOT 为消除告警改变独立队列契约、吞异步失败或扩大抑制范围。改变底层并发协议仍须
  按根 AGENTS 先说明并确认。每批在最终代码上验证，MUST NOT 把旧告警行号当当前定位。
- 被否决方向及理由：不全局关闭检查器，不将所有告警机械替换；刻意身份比较与契约测试
  需要保留，旧诊断建议以实际调用目的和当前实现为准。

以下诊断清单是初次 `mvn -o clean test-compile` 的历史输入，不是每条处置都已批准。
已提交批次见 `f12c1b8`、`3d03967`；随后暴露的测试与核心告警也已按本提案的
逐项分诊原则处理，保持并发协议与测试交错语义不变。

基线命令：`mvn -o clean test-compile`。实际输出 103 条 WARNING；以下先记录识别结果，处置状态会随每批修改更新。

## 逐条识别

### ThreadPriorityCheck（14 条）

- `src/main/java/io/github/monadrome/parallelinscope/ExecutionPhaseHintFuture.java:239 [ThreadPriorityCheck]`：循环等待中使用 `Thread.yield()`，是忙等，应改为有界短暂休眠或更合适的同步信号。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:116 [ThreadPriorityCheck]`：状态轮询使用 `Thread.yield()`，应改为短暂休眠以避免烧 CPU。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:125 [ThreadPriorityCheck]`：Future 取消轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:134 [ThreadPriorityCheck]`：中断标志轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:219 [ThreadPriorityCheck]`：退出信号轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/ExecutionPhaseCartesianTest.java:134 [ThreadPriorityCheck]`：测试等待原子标志时忙等，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/ExecutionPhaseHintFutureTest.java:79 [ThreadPriorityCheck]`：测试等待 runner 状态时忙等，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/ParRuntimeGlobalSlotTest.java:80 [ThreadPriorityCheck]`：竞态测试用 `yield()` 偏置调度，需改成不烧 CPU 且仍保留竞态窗口的等待方式。
- `src/test/java/io/github/monadrome/parallelinscope/ParentChildResultRetentionTest.java:249 [ThreadPriorityCheck]`：报告轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/ParentChildResultRetentionTest.java:258 [ThreadPriorityCheck]`：原子标志轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/SlidingWindowSubmitterTest.java:901 [ThreadPriorityCheck]`：随机延迟兜底使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueueTest.java:915 [ThreadPriorityCheck]`：队列测试状态轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueueTest.java:929 [ThreadPriorityCheck]`：队列测试状态轮询使用 `Thread.yield()`，应改为短暂休眠。
- `src/test/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueueCartesianTest.java:175 [ThreadPriorityCheck]`：队列测试状态轮询使用 `Thread.yield()`，应改为短暂休眠。

### FutureReturnValueIgnored（40 条）

以下调用都绑定了会异步完成的 Future；返回的聚合 Future 不能丢弃，应接住并用断言或等待验证完成，避免吞掉异步异常。

- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:281 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:298 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:315 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:334 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:352 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:385 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:399 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:416 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:432 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:446 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:464 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:476 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:491 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:504 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:519 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:543 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:563 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:581 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:603 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:615 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:636 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:653 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:669 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:691 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:708 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:726 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:770 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:790 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:808 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:818 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:844 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTokenTest.java:864 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并断言聚合状态。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:61 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并验证取消聚合完成。
- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:97 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并验证取消聚合完成。
- `src/test/java/io/github/monadrome/parallelinscope/CheckpointsOutcomeTest.java:101 [FutureReturnValueIgnored]`：提交结果被丢弃，应等待或断言其完成，避免吞异常。
- `src/test/java/io/github/monadrome/parallelinscope/ExecutionPhaseHintFutureTest.java:404 [FutureReturnValueIgnored]`：Future 返回值被丢弃，应明确验证异步失败。
- `src/test/java/io/github/monadrome/parallelinscope/SlidingWindowResourceBoundTest.java:36 [FutureReturnValueIgnored]`：占用 worker 的提交 Future 被丢弃，应接住并确保异常可见。
- `src/test/java/io/github/monadrome/parallelinscope/TaskBatchResultTest.java:220 [FutureReturnValueIgnored]`：绑定结果被丢弃，应接住并验证批次取消完成。
- `src/test/java/io/github/monadrome/parallelinscope/VirtualDeadlineTest.java:41 [FutureReturnValueIgnored]`：虚拟时钟绑定结果被丢弃，应接住并断言聚合完成。
- `src/test/java/io/github/monadrome/parallelinscope/VirtualDeadlineTest.java:60 [FutureReturnValueIgnored]`：虚拟时钟绑定结果被丢弃，应接住并断言聚合完成。

### CanonicalDuration（10 条）

- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:96 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:97 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:98 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:107 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:116 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:117 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:118 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:127 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:128 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。
- `src/test/java/io/github/monadrome/parallelinscope/TaskCompletionTest.java:129 [CanonicalDuration]`：纳秒常量可用更清晰的毫秒表达，应改为等价的规范单位。

### ReferenceEquality（8 条）

- `src/main/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueue.java:351 [ReferenceEquality]`：节点身份比较是队列链表语义，属于真误报，应保留并加窄抑制说明。
- `src/main/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueue.java:428 [ReferenceEquality]`：节点身份比较是队列链表语义，属于真误报，应保留并加窄抑制说明。
- `src/main/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueue.java:489 [ReferenceEquality]`：节点身份比较是迭代器链路语义，属于真误报，应保留并加窄抑制说明。
- `src/main/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueue.java:518 [ReferenceEquality]`：节点身份比较是迭代器链路语义，属于真误报，应保留并加窄抑制说明。
- `src/main/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueue.java:1055 [ReferenceEquality]`：队列对象自身身份用于 toString 特殊标识，属于真误报，应保留并加窄抑制说明。
- `src/main/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueue.java:1186 [ReferenceEquality]`：节点身份比较用于遍历分裂边界，属于真误报，应保留并加窄抑制说明。
- `src/test/java/io/github/monadrome/parallelinscope/SlidingWindowResourceBoundTest.java:96 [ReferenceEquality]`：线程对象身份用于排除当前线程，属于真误报，应保留并加窄抑制说明。
- `src/test/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueueTest.java:295 [ReferenceEquality]`：测试验证同一队列实例，身份比较是断言语义，应保留并加窄抑制说明。

### MissingOverride（6 条）

- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:173 [MissingOverride]`：实现 `ExecutorService.shutdown` 缺少 `@Override`，直接补注解。
- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:175 [MissingOverride]`：实现 `ExecutorService.shutdownNow` 缺少 `@Override`，直接补注解。
- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:179 [MissingOverride]`：实现 `ExecutorService.isShutdown` 缺少 `@Override`，直接补注解。
- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:183 [MissingOverride]`：实现 `ExecutorService.isTerminated` 缺少 `@Override`，直接补注解。
- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:187 [MissingOverride]`：实现 `ExecutorService.awaitTermination` 缺少 `@Override`，直接补注解。
- `src/test/java/io/github/monadrome/parallelinscope/MultiTaskContextTest.java:191 [MissingOverride]`：实现 `Executor.execute` 缺少 `@Override`，直接补注解。

### UnusedVariable（4 条）

- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:146 [UnusedVariable]`：`WorkFixture` 的 `workload` 字段未读，应删除字段并保留构造所需参数。
- `src/test/java/io/github/monadrome/parallelinscope/CheckpointsOutcomeTest.java:44 [UnusedVariable]`：参数 `semaphore` 未读，应从测试辅助方法签名删除或用于断言。
- `src/test/java/io/github/monadrome/parallelinscope/ScopedTaskContractTest.java:337 [UnusedVariable]`：参数 `global` 未读，应删除该参数并更新调用点。
- `src/test/java/io/github/monadrome/parallelinscope/SlidingWindowResourceBoundTest.java:26 [UnusedVariable]`：未使用的 `ELEMENTS` 常量应删除。

### ExposedPrivateType（4 条）

- `src/test/java/io/github/monadrome/parallelinscope/CancellationTriggerCartesianTest.java:50 [ExposedPrivateType]`：参数化测试方法暴露私有枚举，应将 `Trigger` 与 `Workload` 放宽为包级私有。
- `src/test/java/io/github/monadrome/parallelinscope/ExecutionPhaseCartesianTest.java:38 [ExposedPrivateType]`：参数化测试方法暴露私有 `Timing`，应放宽为包级私有。
- `src/test/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueueCartesianTest.java:49 [ExposedPrivateType]`：参数化测试暴露私有 `Waiter`/`Release`，应放宽类型可见性。
- `src/test/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueueCartesianTest.java:92 [ExposedPrivateType]`：参数化测试暴露私有 `ProducerRelease`，应放宽类型可见性。

### BadInstanceof（4 条）

- `src/test/java/io/github/monadrome/parallelinscope/TaskBatchResultTest.java:83 [BadInstanceof]`：`TaskFuture<String>` 已是 `TaskFuture` 子类型，类型判断应改为非空判断。
- `src/test/java/io/github/monadrome/parallelinscope/TaskFutureTest.java:56 [BadInstanceof]`：`TaskFuture<String>` 已是目标类型子类，类型判断应改为非空判断。
- `src/test/java/io/github/monadrome/parallelinscope/TaskFutureTest.java:88 [BadInstanceof]`：`TaskFuture<String>` 已是目标类型子类，类型判断应改为非空判断。
- `src/test/java/io/github/monadrome/parallelinscope/TaskFutureTest.java:133 [BadInstanceof]`：`TaskFuture<?>` 已是目标类型子类，类型判断应改为非空判断。

### BooleanLiteral（3 条）

- `src/test/java/io/github/monadrome/parallelinscope/TaskGroupBodyCompletionTest.java:409 [BooleanLiteral]`：布尔值断言可直接表达期望值，应改成更清晰的布尔断言。
- `src/test/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueueCartesianTest.java:139 [BooleanLiteral]`：布尔表达式写法冗余，应改为直接布尔断言。
- `src/test/java/io/github/monadrome/parallelinscope/queue/VariableLinkedBlockingQueueCartesianTest.java:196 [BooleanLiteral]`：布尔表达式写法冗余，应改为直接布尔断言。

### UnnecessaryAsync（2 条）

- `src/test/java/io/github/monadrome/parallelinscope/ExecutionPhaseCartesianTest.java:40 [UnnecessaryAsync]`：局部 `AtomicBoolean` 不逃逸并无并发需要，应改为普通布尔容器或返回值。
- `src/test/java/io/github/monadrome/parallelinscope/TaskFutureTest.java:473 [UnnecessaryAsync]`：局部并发容器不逃逸，应改为非并发类型而不改变断言。

### 需要逐条判断的告警（6 条）

- `src/main/java/io/github/monadrome/parallelinscope/Checkpoints.java:214 [WaitNotInLoop]`：条件等待当前直接调用一次，需要确认调用方是否已有外层状态条件；若没有则必须补循环。
- `src/main/java/io/github/monadrome/parallelinscope/Checkpoints.java:613 [TypeParameterUnusedInFormals]`：泛型只出现在返回类型，需确认调用点后移除类型参数或改为安全签名。
- `src/test/java/io/github/monadrome/parallelinscope/CheckpointsTest.java:327 [LockNotBeforeTry]`：锁获取与释放 `try` 之间有语句，需调整到紧邻 `try` 并确认异常路径语义。
- `src/test/java/io/github/monadrome/parallelinscope/ExecutorRuntimeTest.java:80 [ErroneousThreadPoolConstructorChecker]`：测试故意构造无界队列来验证饥饿诊断，应保留语义并考虑窄抑制及理由。
- `src/test/java/io/github/monadrome/parallelinscope/ParRuntimeGlobalSlotTest.java:122 [RethrowReflectiveOperationExceptionAsLinkageError]`：反射字段缺失代表测试环境/二进制链接错误，应按建议抛出 `LinkageError` 并保留原始原因。
- `src/main/java/io/github/monadrome/parallelinscope/queue/DrainingBlockingQueue.java:1267 [AssignmentExpression]`：循环内赋值表达式降低可读性，应拆成独立赋值语句。

## 处置记录

### 批次 1：ThreadPriorityCheck

已处理 14 条：等待循环改为 1ms 休眠；`ExecutionPhaseHintFuture` 的无检查异常清理等待改为 `LockSupport.parkNanos`。未添加抑制，也未改变等待的超时边界或中断协议。批次验证：`mvn -o clean test-compile` 成功，Error Prone 告警从 103 条降至 89 条。

后续批次继续在此记录改动条数、抑制条数、不改条数及剩余告警。

### 后续批次处置

合并后新增的测试告警通过值断言、保留嵌套类型形状的窄抑制和原始布尔值修复；两个
`EmptyCatch` 位于必须保持非抛出的日志路径和预期消费失败钉子的等待路径，分别保留方法级
抑制。检查点异常转译与队列分裂赋值的改写只消除告警，不改变运行时协议。

最终完整源码重编译不再产生 Error Prone 模式告警；Spotless 检查和完整测试均通过。

### 独立审查处置

独立只读审查未发现并发、契约、Java 8 或泛型缺陷。审查指出条件等待适配器的单次 signal
语义需要在 Javadoc 中明确；`Checkpoints.checkAwait(Condition, ...)` 已补充该说明，保留
现有窄抑制与调用方谓词循环责任。
