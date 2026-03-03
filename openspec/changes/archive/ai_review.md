vformation 项目问题锐评

一、设计层面的问题

1. CancellationToken.lateBind() 的 fail-fast 逻辑有隐患

CancellationToken.java:73-96 — lateBind() 将 allAsList 的异常通过 catchingAsync 转换为 immediateCancelledFuture()，然后接 withTimeout。但这里有个语义问题：当任一任务失败时，allAsList 会立即失败 ，catchingAsync 将其转为 cancelled future，然后 onFailure 里走的是 else 分支（FAIL_FAST_CANCELED）。即使用户没有设置 failFast=true，这个逻辑也会触发。ParOptions.failFast 这个选项实际上在整个代码路径中从未被读取和判断——lateBind 无条件地做了 fail-fast。这意味着 failFast 选项形同虚设。

2. tryPurgeOnTimeout 永远不会触发

Par.java:203-209 — tryPurgeOnTimeout 监听的是 submitCanceller 的 TimeoutException，但 submitCanceller 是 submitter pool 的 submit() 返回的 future，它本身不会抛 TimeoutException（timeout 是绑在 failFastFuture 上的，不是 submitCanceller 上）。这意味着 purge 逻辑是死代码。

3. Checkpoints.checkpoint() 的 taskName 校验逻辑诡异

Checkpoints.java:37-38 — 当 options.getTaskName() 等于 "task" 时直接跳过检查，当 taskName 和 options.getTaskName() 不匹配时也跳过。这意味着：
- 默认 taskName 为 "task" 时，checkpoint 永远不生效
- 只有调用方传入的 taskName 和当前线程上下文的 taskName 严格匹配时才检查

这个设计意图可能是为了避免嵌套场景下误杀内层任务，但逻辑过于隐晦，没有注释说明，容易让使用者以为 checkpoint 不起作用。

4. report() 方法的返回类型是 Map.Entry — 类型滥用

AsyncBatchResult.java:53 — 用 Map.Entry<Map<CffuState, Integer>, Throwable> 做返回值是一种反模式。应该定义一个专门的 BatchReport 类。Map.Entry 本身不表达语义，调用者需要记住 key 是什么、value 是什么。

5. PurgeService 全静态设计与 ParConfig 实例化设计矛盾

PurgeService 的所有字段和方法都是 static 的（全局单例），包括 STALE_COUNTERS、rateLimiter 等。但 ParConfig 已经改为实例化设计，允许多个隔离配置。如果有两个 ParConfig 实例使用不同的 executor 注册表，PurgeService 的全局 counter 会混在一起，且 configure() 也是全局生效的。

类似的问题也存在于 TaskGraph——它使用 ThreadLocal，但 ParConfig 的 livelockDetectionEnabled 是实 例级的，不同 ParConfig 实例共享同一个 TaskGraph。

二、并发安全问题

6. ScopedCallable.startTime 和 endTime 非 volatile

ScopedCallable.java:55-56 — startTime 和 endTime 在 call() 中写入，在 notifyListeners() 中通过 waitTime() / executionTime() 读取。虽然两者在同一线程内，但 executionTime() 和 waitTime() 是 public 方法，外部线程（如 listener 回调线程）读取这些值时存在可见性问题。如果 TaskListener 在其他线程异 步处理 TaskEvent，它拿到的 submitTime 等原始 nanos 值可能和 ScopedCallable 的时间不一致。

好在 notifyListeners 在 call() 同一线程内执行，且 TaskEvent 是值拷贝（new TaskEvent(... submitTime, startTime, endTime ...)），所以当前实现没有实际 bug，但 API 设计上把 executionTime()/waitTime() 暴露为 public 是有风险的。

7. PurgeService.counter 的 check-then-act 竞态

PurgeService.java:103-108 — counter.get() >= threshold 检查和 counter.set(0) 不是原子操作。两个 并发 purge 任务可能同时通过 threshold 检查，虽然 rateLimiter.tryAcquire() 提供了一定保护，但 counter.set(0) 可能吞掉另一个线程刚加上去的计数。应该用 counter.getAndSet(0) 或 CAS 循环。

8. ParConfig.registerExecutor() 中两个 Map 的非原子更新

ParConfig.java:327-328 — executorRawRegistry.put() 和 executorRegistry.put() 是两次独立操作。如 果一个线程在两次 put 之间调用 resolveThreadPool()，可能看到不一致状态（raw registry 有但 listening registry 还没有，或反之）。对于注册通常发生在启动阶段这不是实际问题，但作为线程安全的库来说不够严谨。

三、测试覆盖的严重缺失

9. 最核心的路径几乎没有测试
   ┌─────────────────────────────────────────────┬──────────┐
   │                 未覆盖路径                  │ 严重程度 │
   ├─────────────────────────────────────────────┼──────────┤
   │ CancellationToken.lateBind() 的所有状态转换 │ 致命     │
   ├─────────────────────────────────────────────┼──────────┤
   │ 端到端 timeout 测试（任务超时后被取消）     │ 致命     │
   ├─────────────────────────────────────────────┼──────────┤
   │ 端到端 fail-fast 测试（一个失败后取消其他） │ 致命     │
   ├─────────────────────────────────────────────┼──────────┤
   │ ThreadRelay 跨线程上下文传播                │ 高       │
   ├─────────────────────────────────────────────┼──────────┤
   │ 嵌套 Par 调用（task 内再调 map）         │ 高       │
   ├─────────────────────────────────────────────┼──────────┤
   │ ScopedCallable 完整生命周期                 │ 高       │
   ├─────────────────────────────────────────────┼──────────┤
   │ PurgeService 任何路径                       │ 中       │
   └─────────────────────────────────────────────┴──────────┘
   现有的 ~52 个测试大部分覆盖的是 happy path 和数据结构层面。框架的核心价值——超时取消、fail-fast、上下文传播、滑动窗口——在端到端层面基本是裸奔状态。

10. 存在计时敏感的 flaky 测试

- ParTest.testTaskListener_invoked() 用 Thread.sleep(100) 等回调
- ParTest.testParallelism_limit() 用 <= 5 断言并发度为 2 的场景（过于宽松）
- ConcurrentLimitExecutorTest.testSubmitAll_slidingWindow() 用 Thread.sleep(30) 控制节奏

这些在 CI 环境下迟早会 flaky。应该用 CountDownLatch / Phaser / Awaitility 替代。

四、API 设计问题

11. forEach 接收 Collection，map 接收 List

Par.java:96 vs Par.java:120 — 入参类型不一致，没有明显理由。map 需要保序可以理解，但 forEach 返回的 AsyncBatchResult 里的 future list 同样和输入顺序对应（通过 stream 的 collect(toImmutableList()) 保序）。建议统一为 Collection 或都要求 List。

12. ParOptions.enableParallel 未实现

ParOptions 有 enableParallel 字段但在 Par.executeParallel() 中完全没有读取和判断。如果设为 false，行为和 true 完全一样。

13. Timer 线程池 core size 硬编码 16

ParConfig.java:122 — 16 个核心线程的 ScheduledThreadPoolExecutor 作为全局 timer，对于轻量级使用 场景偏重，对于重度使用场景又可能不够。且没有提供配置入口。

五、工程细节

14. @SuppressWarnings("all") 在 PurgeService 上

PurgeService.java:30 — 直接压制全部警告是 code smell。应该只压制具体需要压制的警告类型。

15. CLAUDE.md 和实际代码不一致

CLAUDE.md 提到 Attachable, FutureInspector, ListeningExecutorAdapter 等类，但实际代码中这些已经 不存在或被重构了。文档滞后会误导。

16. cffu2 依赖仅用于 CffuState 枚举和 exceptionNow

AsyncBatchResult 和 FutureInspectorTest 是唯二使用 cffu2 的地方，且只用了 CffuState 和 CompletableFutureUtils.state()/exceptionNow()。为了两个工具方法引入一整个库，增加了依赖复杂度。这几行逻辑完全可以内联实现。

  ---
总结

项目的架构思路是清晰的——sliding window、late binding、two-map relay、cooperative cancellation 这套设计在结构化并发领域是合理的。代码也比较干净，没有明显的代码腐化。

最大的风险点：
1. fail-fast 选项形同虚设（逻辑 bug）
2. tryPurgeOnTimeout 是死代码（逻辑 bug）
3. 核心路径零测试（lateBind、ThreadRelay、嵌套 Par）
4. PurgeService/TaskGraph 的全局静态设计与 ParConfig 实例化设计矛盾

如果这个库要对外发布到 Maven Central，上面前两个是需要立即修复的逻辑 bug，第三个是必须补齐的测试，第四个是架构层面需要决策的问题。