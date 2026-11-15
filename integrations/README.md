**中文** | [**English**](README.en.md)

# parallel-in-scope-integrations

`parallel-in-scope` 与 Resilience4j、Sentinel、Spring 的集成样例。这是独立的消费者工程，与
[`demo/`](../demo/README.md) 分开，第三方依赖不会进入 demo 或库本身。

| 依赖 | 版本 | 选择理由 |
|---|---|---|
| Resilience4j（`circuitbreaker`、`retry`） | 1.7.1 | 2.x 需要 Java 17 |
| Sentinel（`sentinel-core`） | 1.8.10 | 1.8 最新稳定版 |
| Spring Framework（`spring-context`） | 5.3.39 | 6.x 需要 Java 17 |

代码写法在 Resilience4j 2.x、Spring 6 / Boot 3 上相同。

## 构建与运行

```bash
mvn install -DskipTests -Dmaven.javadoc.skip=true   # 仓库根目录，JDK 25
cd integrations
mvn test
mvn exec:java                                        # integrations.app.QuoteApplication
```

`QuoteApplication` 先对健康的模拟服务报价 12 个 SKU，然后让定价服务宕机：熔断器打开前报价失败，
打开后定价降级为“不可用”。

## 唯一的规则：在任务体内做装饰

Resilience4j 和 Sentinel 的单次调用状态都绑定在调用线程上。许可要在任务体内、工作线程上、围绕一次下游调用获取。
在调用方围绕 `Par.map` 获取的许可会把整个扇出算成一次调用，并在错误的线程上释放。

```java
TaskBatchResult<Long> prices = downstreamPar.map(skus,
        sku -> ResilientCall.call(breaker, retry, () -> pricing.priceCents(sku), open -> UNAVAILABLE),
        BatchOptions.timeout("prices", Duration.ofSeconds(2)).parallelism(8));
```

并发与超时仍由本库负责：`parallelism` 限制一次扇出，批次或任务组的截止时间限制整个调用。样例不使用
Resilience4j 的 `TimeLimiter` 和 `ThreadPoolBulkhead`，因为两者都会把调用移到作用域之外的线程，取消信号到不了那里。

## 取消不是下游故障

fail-fast、截止时间、`cancelOn` 会中断运行中的任务体，任务体随后以取消形态的异常结束。熔断器或 Sentinel
若把它们计为失败，一个元素失败就会让整个下游熔断。`Cancellations.causedByCancellation` 识别这类失败：作用域已取消或超时、
中断标志已置位，或异常链中含 `CancellationException`、`InterruptedException`、`ClosedByInterruptException`、
非超时的 `InterruptedIOException`。`SocketTimeoutException` 仍算下游故障。

## Resilience4j：`integrations.resilience4j.ResilientCall`

- `circuitBreakerConfig()` 忽略由取消导致的失败，许可被释放但不记录结果。`ResilientCallTest` 对比了两种配置：
  四个元素中一个失败、三个被 fail-fast 取消时，本配置熔断器保持 CLOSED、只记录 1 次失败；默认配置记录 4 次并打开。
- `retryConfig()` 不重试取消，也不重试 `CallNotPermittedException`。再次调用 `retryOnException` 会覆盖该谓词，
  自定义谓词请与 `ResilientCall.retryable` 组合。
- 每次尝试先调用 `Checkpoints.checkpoint()`：退避期间请求被非中断式取消后，不会再发起下一次尝试。
- Resilience4j 1.7 用 `Thread.sleep` 做重试退避，退避被中断时会**以未声明的 `InterruptedException` 抛出，且中断标志已被清除**。
  `call` 恢复中断标志，并把它转换为以原异常为 cause 的 `CancellationException`。因此退避期间到达截止时间，
  元素会在截止时间内以 `TIMEOUT` 结束，而不是等退避结束。
- 熔断器打开时返回降级值，该元素以降级值成功。其他失败在重试耗尽后照常让元素失败，并触发批次的 fail-fast。

## Sentinel：`integrations.sentinel.SentinelGuard`

- `call(resource, downstream, whenBlocked)` 在同一个任务体内 `SphU.entry` 并 `exit`。`BlockException` 降级为兜底值。
- 业务异常通过 `Tracer.traceEntry` 上报，取消不上报。fail-fast 取消的兄弟任务因此不会触发按异常比例或异常数的降级规则。
- Sentinel 的 Context 是普通 `ThreadLocal`，本库不传播它。工作线程上按来源（`limitApp`）的规则看到的是空来源。
  `callWithOrigin(context, origin, ...)` 接收在请求线程上取得的来源，并在任务体内进入、退出 Context，不会残留在池化线程上。
  线程上已有 Context 时（例如任务体在调用方线程上内联执行），保留原有 Context。
- 线程数（THREAD）规则是“先检查后累加”，首批入口并发时可能放行超过阈值，应视为近似并发上限。
- `SentinelFlowRules` 把流控规则 Bean 加载到 JVM 全局的 `FlowRuleManager`，关闭时清空。生产环境应改用动态规则数据源。

## Spring：`integrations.spring`

- `ParallelInScopeConfiguration` 注册物理 `ThreadPoolExecutor` Bean。Spring 的 `ThreadPoolTaskExecutor`
  对运行时的线程饥饿风险检查不透明，其 `TaskDecorator` 也不作用于本库提交的任务。会等待的任务体（`quotes` 入口）和被等待的工作
  （`downstream` 入口）使用不同线程池，`QuoteServiceTest` 断言 `executorWaitReport()` 中没有环。
- 关闭顺序：`ParRuntimeLifecycle`（`SmartLifecycle`，phase 为 `DEFAULT_PHASE - 4096`）在 stop 阶段关闭运行时并
  `awaitQuiescence`，此时 Spring 还没有销毁任何 Bean，任务体结束前仍可使用客户端和线程池。之后线程池 Bean 通过 `destroyMethod`
  关闭。Boot 的 Web 服务器优雅关闭更早停止，请求会先停止进入。
- 配置使用 `proxyBeanMethods = false` 和普通 `@Bean` 方法，Boot 应用可直接导入。项目刻意不提供 starter，见
  [想法墓地](../docs/zh/design/idea-graveyard.md)。

## 组合服务：`integrations.app.QuoteService`

`quote(caller, sku)` 运行一个任务组：价格和库存在 `downstream` 上并行，combine 组装 `Quote`。定价经过重试和熔断，
库存经过 Sentinel。`quoteAll` 在 `quotes` 上为每个 SKU 扇出一个任务组，批次截止时间会限制每个嵌套任务组的截止时间。

```java
return runtime.group("quote", QUOTE_TIMEOUT)
        .par(PRICE, downstream, () -> price(sku))
        .par(STOCK, downstream, () -> stock(caller, sku))
        .combineValues(QUOTE, downstream, values -> new Quote(sku, values.valueOf(PRICE), values.valueOf(STOCK)))
        .runAll()
        .terminalValueOrThrow();
```
